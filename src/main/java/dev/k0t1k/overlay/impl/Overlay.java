package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.IntBinaryOperator;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * The overlay: opened with Shift+Tab over the running game, like the Steam overlay. It owns the
 * render-thread state and is driven by the agent's hooks: {@link #onSwap} once per frame and the
 * input methods from {@link GlfwPlatform}'s wrapped callbacks.
 */
public final class Overlay {
    private static final int ATLAS = 2048;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] TABS = {"tab.chat", "tab.notes", "tab.perf"};

    private final Options options;
    private final Strings strings;
    private final GlfwPlatform platform = new GlfwPlatform(this);
    private final Perf perf = new Perf();
    private final long started = System.currentTimeMillis();

    /** Work posted to the render thread, run at the start of the next frame. */
    private final ConcurrentLinkedQueue<Runnable> renderQueue = new ConcurrentLinkedQueue<>();
    private final Executor render = renderQueue::add;
    private final ScheduledExecutorService ioThread = Executors.newSingleThreadScheduledExecutor(daemon("io"));
    private final ExecutorService searchThread = Executors.newSingleThreadExecutor(daemon("search"));
    /** {@link #ioThread} with failures reported instead of dropped inside a future. */
    private final Executor io = task -> ioThread.execute(() -> {
        try {
            task.run();
        } catch (RuntimeException e) {
            report(e);
        }
    });

    private final ChatHistory chat;
    private final Notes notes;
    private final ChatView chatView;
    private final NotesView notesView;
    private final PerfView perfView;

    private Gl gl;
    private Font.Atlas atlas;
    private Ui ui;
    private boolean broken;
    private volatile boolean open;
    private int tab;
    private final int[] sizes = new int[4];
    /** Framebuffer pixels per window coordinate, from the last frame; converts cursor positions. */
    private float pixelRatio = 1;
    /** Keys and buttons whose press reached the game, so only their releases are passed on while open. */
    private final java.util.Set<Integer> gameKeys = new java.util.HashSet<>();
    private final java.util.Set<Integer> gameButtons = new java.util.HashSet<>();

    private static final class Toast {
        final String title;
        final String body;
        final long until;

        Toast(String title, String body, long until) {
            this.title = title;
            this.body = body;
            this.until = until;
        }
    }

    private final List<Toast> toasts = new ArrayList<>();

    public Overlay(String args) {
        options = new Options(args);
        strings = options.strings;
        try {
            options.loadSettings();
        } catch (IOException e) {
            report(e);
        }
        chat = new ChatHistory(options.gameDir, options.dataDir, ioThread, searchThread, render, this::report);
        notes = new Notes(options.dataDir, io, render, this::report);
        chatView = new ChatView(chat, strings, this);
        notesView = new NotesView(notes, strings);
        perfView = new PerfView(perf, options, () -> io.execute(options.saveTask()));
        chat.onNew = this::checkMention;
        chat.start();
        notes.load();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "k0t1k-overlay-exit"));
        toast(strings.get("welcome"), strings.get("welcome.body"));
        log("started, data in " + options.dataDir);
    }

    /** The hooks the agent calls, as JDK types: {swap, wrap callback, is open, cursor mode}. */
    public Object[] hooks() {
        LongConsumer swap = this::onSwap;
        BiFunction<String, Object, Object> wrap = platform::wrap;
        BooleanSupplier isOpen = this::isOpen;
        IntBinaryOperator inputMode = platform::onInputMode;
        return new Object[] {swap, wrap, isOpen, inputMode};
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, "k0t1k-overlay-" + name);
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    static void log(String message) {
        System.out.println("[k0t1k-overlay] " + message);
    }

    /** Reports a recoverable failure (disk, parsing) without disabling the overlay. */
    void report(Throwable error) {
        System.err.println("[k0t1k-overlay] " + error);
        error.printStackTrace();
    }

    /**
     * Last chance on JVM exit: the io thread is a daemon and may never run queued work, so the newest
     * chat lines and unsaved notes are written from here.
     */
    private void saveOnExit() {
        chat.pollNow();
        notes.saveNow();
    }

    /** Disables the overlay after a failure in drawing or input, leaving the game untouched. */
    void fail(Throwable error) {
        if (broken) {
            return;
        }
        broken = true;
        System.err.println("[k0t1k-overlay] disabled after an error:");
        error.printStackTrace();
        renderQueue.clear();
        notes.flush(true);
        if (open) {
            open = false;
            try {
                platform.restoreCursor();
            } catch (RuntimeException | LinkageError e) {
                e.printStackTrace();
            }
        }
    }

    boolean isOpen() {
        return open && !broken;
    }

    void toast(String title, String body) {
        toasts.add(new Toast(title, body, System.currentTimeMillis() + 4500));
        if (toasts.size() > 4) {
            toasts.remove(0);
        }
    }

    private void checkMention(ChatHistory.Line line) {
        String name = options.username;
        if (name.isEmpty() || open) {
            return;
        }
        String text = line.text.toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        // Ignore the player's own messages, which start with "<Name>".
        if (text.startsWith("<" + lower + ">")) {
            return;
        }
        int at = text.indexOf(lower);
        while (at >= 0) {
            boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)) && text.charAt(at - 1) != '_';
            int end = at + lower.length();
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '_';
            if (startOk && endOk) {
                toast(strings.get("chat.mention"), line.text);
                return;
            }
            at = text.indexOf(lower, at + 1);
        }
    }

    // ---- input, from the wrapped GLFW callbacks; each returns true when the game must not see it ----

    boolean key(int key, int action, int mods) {
        if (broken || ui == null) {
            return false;
        }
        boolean press = action != Keys.RELEASE;
        if (!press && !gameKeys.remove(key) && open) {
            // The game never saw this press (the overlay took it), so it must not see the release.
            return true;
        }
        if (press && key == Keys.TAB && (mods & Keys.MOD_SHIFT) != 0
                && (mods & (Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER)) == 0) {
            if (action == GLFW.GLFW_PRESS) {
                setOpen(!open);
            }
            return true;
        }
        if (!open) {
            if (press) {
                gameKeys.add(key);
            }
            return false;
        }
        if (!press) {
            // Released after the overlay opened: the game saw the press, so it gets the release too
            // and the key does not stay down.
            return false;
        }
        if (key == Keys.ESCAPE) {
            setOpen(false);
        } else {
            ui.onKey(key, mods);
        }
        return true;
    }

    boolean character(int codepoint) {
        if (!isOpen()) {
            return false;
        }
        ui.onChar(codepoint);
        return true;
    }

    boolean button(int button, int action) {
        boolean press = action != Keys.RELEASE;
        if (!isOpen()) {
            if (press) {
                gameButtons.add(button);
            } else {
                gameButtons.remove(button);
            }
            return false;
        }
        ui.onButton(button, press);
        // Same rule as for keys: a release passes only when the game saw the press.
        return press || !gameButtons.remove(button);
    }

    boolean cursor(double x, double y) {
        if (!isOpen()) {
            return false;
        }
        ui.onMove((float) (x * pixelRatio / ui.scale), (float) (y * pixelRatio / ui.scale));
        return true;
    }

    boolean scroll(double dy) {
        if (!isOpen()) {
            return false;
        }
        ui.onScroll(dy);
        return true;
    }

    private void setOpen(boolean value) {
        if (open == value) {
            return;
        }
        ui.resetInput();
        if (value) {
            open = true;
            platform.showCursor();
            platform.sizes(sizes);
            ui.onMove(sizes[2] / 2f * pixelRatio / ui.scale, sizes[3] / 2f * pixelRatio / ui.scale);
            toasts.clear();
        } else {
            open = false;
            notes.flush(true);
            platform.restoreCursor();
        }
    }

    // ---- frame ----

    private void onSwap(long window) {
        if (broken) {
            return;
        }
        try {
            perf.frame();
            platform.window = window;
            for (Runnable task; (task = renderQueue.poll()) != null; ) {
                task.run();
            }
            if (GLFW.glfwGetCurrentContext() == 0) {
                return; // no OpenGL here (e.g. Vulkan); nothing to draw into
            }
            long now = System.currentTimeMillis();
            for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
                if (it.next().until < now) {
                    it.remove();
                }
            }
            if (!open && !options.hud && toasts.isEmpty()) {
                return; // nothing on screen, so the game's frame stays untouched
            }
            if (gl == null) {
                initGl();
            }
            platform.sizes(sizes);
            if (sizes[0] <= 0 || sizes[1] <= 0 || sizes[2] <= 0) {
                return; // minimised
            }
            pixelRatio = sizes[0] / (float) sizes[2];
            // HiDPI: the larger of the OS scale and the framebuffer density, shrunk if the UI would not fit.
            float scale = Math.max(platform.contentScale(), pixelRatio);
            scale = Math.min(scale, Math.min(sizes[0] / 860f, sizes[1] / 480f));
            ui.scale = Math.max(0.75f, scale);
            if (atlas.full) {
                atlas.reset();
            }
            gl.begin(sizes[0], sizes[1]);
            try {
                draw(sizes[0] / ui.scale, sizes[1] / ui.scale);
            } finally {
                gl.end();
                ui.endFrame();
            }
        } catch (RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    private void initGl() {
        // Throws without a context made current by the game; caught by onSwap.
        GL.getCapabilities();
        gl = new Gl(ATLAS);
        atlas = new Font.Atlas(gl, ATLAS);
        try {
            ui = new Ui(gl, new Font("Inter-Regular.ttf", atlas), new Font("Inter-SemiBold.ttf", atlas), platform);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        perfView.renderer = GL11.glGetString(GL11.GL_RENDERER) + " · OpenGL " + GL11.glGetString(GL11.GL_VERSION);
    }

    private void draw(float w, float h) {
        if (open) {
            drawOverlay(w, h);
        } else if (options.hud) {
            perfView.drawHud(ui, w, h);
        }
        drawToasts(w, h);
    }

    private void drawOverlay(float w, float h) {
        ui.rect(0, 0, w, h, 0xc0060509);

        // Top bar: brand, tabs, clock.
        float barH = 48;
        ui.rect(0, 0, w, barH, 0xf013101c);
        ui.rect(0, barH, w, 1, Ui.LINE);
        float bx = 18;
        bx += ui.text(ui.bold, 16, bx, (barH - ui.lineHeight(ui.bold, 16)) / 2, Ui.ACCENT, "_k0t1k");
        ui.text(ui.regular, 13, bx + 6, (barH - ui.lineHeight(ui.regular, 13)) / 2 + 1, Ui.FAINT, "overlay");

        float tabsW = 0;
        float[] widths = new float[TABS.length];
        for (int i = 0; i < TABS.length; i++) {
            widths[i] = ui.measure(ui.bold, 13, strings.get(TABS[i])) + 32;
            tabsW += widths[i] + 6;
        }
        float tx = (w - tabsW) / 2;
        for (int i = 0; i < TABS.length; i++) {
            if (ui.pill(tx, 9, widths[i], 30, strings.get(TABS[i]), tab == i)) {
                tab = i;
                ui.focus = null;
            }
            tx += widths[i] + 6;
        }

        String clock = LocalTime.now().format(CLOCK);
        float cw = ui.measure(ui.bold, 16, clock);
        ui.text(ui.bold, 16, w - 18 - cw, 7, Ui.FG, clock);
        long minutes = (System.currentTimeMillis() - started) / 60000;
        String session = strings.format("session", String.format("%d:%02d", minutes / 60, minutes % 60));
        float sw = ui.measure(ui.regular, 11, session);
        ui.text(ui.regular, 11, w - 18 - sw, 27, Ui.FAINT, session);

        // Content card.
        float cardW = Math.min(w - 32, 1120);
        float cx = (w - cardW) / 2;
        float cy = barH + 16;
        float cardH = h - cy - 34;
        ui.panel(cx, cy, cardW, cardH, 14, 0xf513101c, Ui.LINE);
        float pad = 18;
        float x = cx + pad;
        float y = cy + pad;
        float innerW = cardW - pad * 2;
        float innerH = cardH - pad * 2;
        if (tab == 0) {
            chatView.draw(ui, x, y, innerW, innerH);
        } else if (tab == 1) {
            notesView.draw(ui, x, y, innerW, innerH);
        } else {
            perfView.draw(ui, x, y, innerW, innerH);
        }

        String hint = strings.get("hint.close");
        ui.text(ui.regular, 12, (w - ui.measure(ui.regular, 12, hint)) / 2, h - 24, Ui.FAINT, hint);
    }

    private void drawToasts(float w, float h) {
        float y = h - 16;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast toast = toasts.get(i);
            float tw = 300;
            List<int[]> lines = ui.wrap(ui.regular, 13, toast.body, tw - 28);
            int shown = Math.min(lines.size(), 3);
            float th = 38 + shown * ui.lineHeight(ui.regular, 13);
            y -= th;
            float x = w - tw - 16;
            ui.panel(x, y, tw, th, 10, 0xf21b1728, 0xff3a3256);
            ui.round(x + 1, y + 10, 3, th - 20, 1.5f, Ui.ACCENT);
            ui.text(ui.bold, 13, x + 14, y + 10, Ui.FG, toast.title);
            for (int l = 0; l < shown; l++) {
                int[] span = lines.get(l);
                ui.text(ui.regular, 13, x + 14, y + 30 + l * ui.lineHeight(ui.regular, 13), Ui.MUTED,
                        toast.body, span[0], span[1]);
            }
            y -= 10;
        }
    }
}
