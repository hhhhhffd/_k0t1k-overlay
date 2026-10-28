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
import java.util.function.Function;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

/**
 * The overlay: opened with Shift+Tab over the running game, like the Steam overlay. It owns the
 * render-thread state and is driven by the agent's hooks through {@link #apply}: a frame hook of the
 * window library or of Vulkan builds a {@link Batch} once per frame and a renderer for the game's
 * graphics API draws it; the window library's input reaches the key, button and cursor methods.
 */
public final class Overlay implements Function<Object[], Object> {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] TABS = {"tab.chat", "tab.notes", "tab.tools", "tab.mods", "tab.profile", "tab.perf"};
    /** No input for this long while playing counts as AFK, and the cat says so. */
    private static final long AFK = 5 * 60_000;
    /** How often the lifetime stats are written. */
    private static final long STATS_SAVE = 60_000;

    private final Options options;
    private final Strings strings;
    private final GlfwPlatform glfw = new GlfwPlatform(this);
    /** The window library in use, known from the first hook it receives. */
    private Platform platform = glfw;
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
    private final GameState game = new GameState();
    private final Stats stats;
    private final Timers timers;
    private final Screenshots screenshots;
    private final Mods mods;
    private final ToolsView toolsView;
    private final ModsView modsView;
    private final ProfileView profileView;
    private Cat cat;
    private final Media media;
    private final Replay replay;
    private GlCapture capture;
    /** Set when reading frames back failed; the replay buffer then stays off for this game. */
    private boolean captureBroken;
    private final CrashGuard crashGuard;
    private long lastInput = System.currentTimeMillis();
    private boolean afk;
    private long nextStatsSave = System.currentTimeMillis() + STATS_SAVE;
    /** Health in the last frame, to notice the moment the player dies. */
    private float lastHealth = -1;
    /** The instance's name: the folder holding the game folder (instances/NAME/minecraft) or the game folder. */
    private final String instance;

    private final Batch batch = new Batch();
    private Font.Atlas atlas;
    private Ui ui;
    private GlRenderer gl;
    private VulkanBackend vk;
    private SdlBackend sdl;
    private LegacyBackend legacy;
    private boolean broken;
    private volatile boolean open;
    /**
     * The thread that draws frames, where the game also reads input and changes the cursor. Mods like
     * Ixeris poll the window on another thread and replay the render thread's window calls there.
     */
    private volatile Thread renderThread;
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
        Runnable saveSettings = () -> io.execute(options.saveTask());
        chatView = new ChatView(chat, strings, this);
        notesView = new NotesView(notes, strings);
        perfView = new PerfView(perf, options, saveSettings);
        Path gameDir = options.gameDir;
        Path parent = gameDir.getParent();
        instance = "minecraft".equals(String.valueOf(gameDir.getFileName())) && parent != null
                ? String.valueOf(parent.getFileName()) : String.valueOf(gameDir.getFileName());
        stats = new Stats(options.dataDir, options.gameDir);
        stats.onUnlock = this::achievement;
        try {
            stats.load();
        } catch (IOException e) {
            report(e);
        }
        timers = new Timers(options.dataDir, io, this::report);
        timers.onFire = this::timerFired;
        timers.load();
        screenshots = new Screenshots(options.gameDir, options.dataDir, ioThread, render, this::report);
        screenshots.onNew = this::screenshot;
        mods = new Mods(options.gameDir, io, render, this::report);
        toolsView = new ToolsView(strings, this, game, timers, screenshots);
        modsView = new ModsView(mods, strings, this);
        profileView = new ProfileView(stats, options, saveSettings);
        media = new Media(ioThread, render, this::report);
        media.onTrack = track -> {
            if (!open) {
                toast(strings.get("media.now"), track);
            }
        };
        replay = new Replay(options.gameDir, this::report);
        crashGuard = new CrashGuard(options.dataDir);
        crashGuard.install();
        chat.onNew = this::onChat;
        chat.start();
        notes.load();
        screenshots.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "k0t1k-overlay-exit"));
        toast(strings.get("welcome"), strings.get("welcome.body"));
        log("started, data in " + options.dataDir);
    }

    /**
     * Every hooked LWJGL call: {@code {class, method, arguments}} before it runs, with the return
     * value appended after it. Answers as {@code Agent.Enter} and {@code Agent.Exit} expect: null to
     * change nothing, new arguments, {@code Boolean.TRUE} to skip the call, or a new return value.
     */
    @Override
    public Object apply(Object[] call) {
        String method = (String) call[1];
        Object[] args = (Object[]) call[2];
        boolean exit = call.length == 4;
        if (broken) {
            // Even disabled, the Vulkan objects made on the game's swapchains and device must go with them.
            if (vk != null && ("vkDestroySwapchainKHR".equals(method) || "vkDestroyDevice".equals(method))) {
                try {
                    vk.call(method, args, null, false);
                } catch (Throwable e) {
                    report(e);
                }
            }
            return null;
        }
        try {
            switch (method) {
                case "glfwSwapBuffers":
                    glfw.window = (Long) args[0];
                    platform = glfw;
                    glFrame();
                    return null;
                case "glfwSetInputMode":
                    return glfw.onInputMode((Integer) args[1], (Integer) args[2]) ? Boolean.TRUE : null;
                case "glfwGetKey":
                case "glfwGetMouseButton":
                    // Keys and buttons read as released by the game while the overlay is open.
                    return isOpen() && onRenderThread() ? Integer.valueOf(0) : null;
                case "glfwSetKeyCallback":
                case "glfwSetCharCallback":
                case "glfwSetCharModsCallback":
                case "glfwSetMouseButtonCallback":
                case "glfwSetCursorPosCallback":
                case "glfwSetScrollCallback": {
                    glfw.window = (Long) args[0];
                    platform = glfw;
                    Object wrapped = args[1] == null ? null : glfw.wrap(method, args[1]);
                    if (wrapped == args[1]) {
                        return null;
                    }
                    Object[] replaced = args.clone();
                    replaced[1] = wrapped;
                    return replaced;
                }
                case "SDL_GL_SwapWindow":
                case "SDL_PollEvent":
                case "SDL_SetWindowRelativeMouseMode":
                case "SDL_WarpMouseInWindow":
                case "SDL_StartTextInput":
                case "SDL_StopTextInput":
                    platform = sdl();
                    return sdl.call(method, args, exit ? call[3] : null, exit);
                // LWJGL 2 (Minecraft 1.12.2 and older); the class tells Keyboard.next from Mouse.next
                case "update":
                case "setGrabbed":
                case "next":
                case "isKeyDown":
                case "isButtonDown":
                case "getDX":
                case "getDY":
                case "getDWheel":
                    platform = legacy();
                    return legacy.call((Class<?>) call[0], method, args, exit ? call[3] : null, exit);
                case "vkCreateSwapchainKHR":
                case "vkDestroySwapchainKHR":
                case "vkGetDeviceQueue":
                case "vkGetDeviceQueue2":
                case "vkQueuePresentKHR":
                case "vkDestroyDevice":
                    return vulkan().call(method, args, exit ? call[3] : null, exit);
                default:
                    return null;
            }
        } catch (Throwable e) {
            // Errors too (e.g. LWJGL's "Out of stack space"): the advice would drop them silently.
            fail(e);
            return null;
        }
    }

    private SdlBackend sdl() throws ReflectiveOperationException {
        if (sdl == null) {
            sdl = (SdlBackend) Class.forName("dev.k0t1k.overlay.impl.SdlPlatform", true, Overlay.class.getClassLoader())
                    .getDeclaredConstructor(Overlay.class)
                    .newInstance(this);
        }
        return sdl;
    }

    private LegacyBackend legacy() throws ReflectiveOperationException {
        if (legacy == null) {
            legacy = (LegacyBackend) Class.forName("dev.k0t1k.overlay.impl.LegacyPlatform", true, Overlay.class.getClassLoader())
                    .getDeclaredConstructor(Overlay.class)
                    .newInstance(this);
        }
        return legacy;
    }

    private VulkanBackend vulkan() throws ReflectiveOperationException {
        if (vk == null) {
            vk = (VulkanBackend) Class.forName("dev.k0t1k.overlay.impl.VkRenderer", true, Overlay.class.getClassLoader())
                    .getDeclaredConstructor(Overlay.class)
                    .newInstance(this);
        }
        return vk;
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
        media.stop();
        replay.stop();
        Path report = stats.saveOnExit();
        if (report != null && options.crashDialog) {
            try {
                crashGuard.onCrash(report, options.lang, sessionSummary());
            } catch (IOException | RuntimeException e) {
                report(e);
            }
        }
    }

    /** What the overlay knows about this session, for the crash window's support bundle. */
    private String sessionSummary() {
        long minutes = (System.currentTimeMillis() - started) / 60000;
        return "Instance: " + instance + "\n"
                + "Session: " + minutes + " min\n"
                + "Renderer: " + perfView.renderer + "\n"
                + String.format(Locale.ROOT, "FPS: %d, frame %.1f ms, 1%% low %.1f ms%n", perf.fps, perf.average, perf.worst)
                + "Heap: " + PerfView.mb(perf.heapUsed) + " / " + PerfView.mb(perf.heapMax) + "\n"
                + "Mods: " + (mods.scanned ? Integer.toString(mods.mods.size()) : "not read") + "\n"
                + "Position: " + (game.inWorld ? game.dimension + " " + game.blockPosition() : "not in a world") + "\n";
    }

    /** Saves the replay buffer (Shift+F2 or the tools tab). */
    void saveReplay() {
        if (!replayAvailable()) {
            toast(strings.get("replay.title"), strings.get(options.replay ? "replay.glOnly" : "replay.off"));
            return;
        }
        if (replay.saving()) {
            return;
        }
        toast(strings.get("replay.title"), strings.get("replay.saving"));
        replay.save(options.replayFormat, file -> render.execute(() -> {
            if (file == null) {
                toast(strings.get("replay.title"), strings.get("replay.failed"));
                return;
            }
            toast(strings.get("replay.saved"), file.getFileName() + "\n" + file.getParent());
            catSays("cat.replay");
        }));
    }

    /** Whether frames are being recorded: OpenGL, the setting on, nothing broken. */
    boolean replayAvailable() {
        return options.replay && capture != null && !captureBroken;
    }

    Replay replay() {
        return replay;
    }

    Options options() {
        return options;
    }

    void saveSettings() {
        io.execute(options.saveTask());
    }

    Media media() {
        return media;
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
        if (vk != null) {
            vk.disable();
        }
    }

    boolean isOpen() {
        return open && !broken;
    }

    /**
     * Whether the current call comes from the render thread. Calls from other threads are replays of
     * render thread calls the overlay already saw (Ixeris), so they pass untouched.
     */
    boolean onRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    void toast(String title, String body) {
        toasts.add(new Toast(title, body, System.currentTimeMillis() + 4500));
        if (toasts.size() > 4) {
            toasts.remove(0);
        }
    }

    String username() {
        return options.username;
    }

    /** Every new chat line: mention toasts, and the player's advancements for the stats and the cat. */
    private void onChat(ChatHistory.Line line) {
        String name = options.username;
        if (name.isEmpty()) {
            return;
        }
        if (isAdvancement(line.text, name)) {
            stats.add("advancements", 1);
            catSays("cat.advancement1", "cat.advancement2", "cat.advancement3");
            return;
        }
        if (!open && mentions(line.text, name)) {
            toast(strings.get("chat.mention"), line.text);
        }
    }

    /** "Name has made the advancement [...]" and its challenge and goal variants, in English and Russian. */
    private static boolean isAdvancement(String text, String name) {
        String lower = text.toLowerCase(Locale.ROOT);
        String me = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(me + " ")) {
            return false;
        }
        String[] markers = {"has made the advancement", "has completed the challenge", "has reached the goal",
            "получил достижение", "получила достижение", "выполнил испытание", "выполнила испытание",
            "достиг цели", "достигла цели"};
        for (String marker : markers) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a chat line names the player as a whole word, not counting the player's own messages. */
    static boolean mentions(String line, String name) {
        if (name.isEmpty()) {
            return false;
        }
        String text = line.toLowerCase(Locale.ROOT);
        String lower = name.toLowerCase(Locale.ROOT);
        // Ignore the player's own messages ("<Name> ...") and lines about the player rather than to them
        // ("Name was slain by...", "Name joined the game").
        if (text.startsWith("<" + lower + ">") || text.startsWith(lower + " ")) {
            return false;
        }
        int at = text.indexOf(lower);
        while (at >= 0) {
            boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1)) && text.charAt(at - 1) != '_';
            int end = at + lower.length();
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end)) && text.charAt(end) != '_';
            if (startOk && endOk) {
                return true;
            }
            at = text.indexOf(lower, at + 1);
        }
        return false;
    }

    private void catSays(String... keys) {
        if (cat == null || !options.cat) {
            return;
        }
        String[] phrases = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            phrases[i] = strings.get(keys[i]);
        }
        cat.say(phrases);
    }

    private void achievement(String id) {
        toast(strings.get("ach.unlocked"), strings.get("ach." + id));
        catSays("cat.achievement");
    }

    private void timerFired(Timers.Timer timer) {
        stats.add("timers", 1);
        toasts.add(new Toast(strings.get("tools.timerDone"), timer.label, System.currentTimeMillis() + 12000));
        if (cat != null && options.cat) {
            cat.say(timer.label);
        }
    }

    private void screenshot(String name) {
        stats.add("screenshots", 1);
        game.update();
        String where = game.available && game.inWorld ? game.blockPosition() : "";
        screenshots.caption(name, instance, game.inWorld ? game.dimension : "", where);
        String body = instance + (where.isEmpty() ? "" : " · " + where) + "\n" + name;
        toast(strings.get("shot.saved"), body);
        catSays("cat.shot1", "cat.shot2");
    }

    /** Once per frame, overlay open or not: game state, timers, stats, deaths and AFK. */
    private void tickWorld(long now) {
        game.update();
        timers.tick(now);
        stats.tick(now);
        if (game.inWorld) {
            if (lastHealth > 0 && game.health <= 0) {
                stats.add("deaths", 1);
                catSays("cat.death1", "cat.death2", "cat.death3");
            }
            lastHealth = game.health;
        } else {
            lastHealth = -1;
        }
        if (!afk && !open && game.inWorld && now - lastInput > AFK) {
            afk = true;
            catSays("cat.afk1", "cat.afk2");
        }
        if (now >= nextStatsSave) {
            nextStatsSave = now + STATS_SAVE;
            io.execute(stats.saveTask());
        }
    }

    private void input() {
        lastInput = System.currentTimeMillis();
        if (afk) {
            afk = false;
            catSays("cat.back");
        }
    }

    // ---- input, from the wrapped GLFW callbacks; each returns true when the game must not see it ----

    boolean key(int key, int action, int mods) {
        if (broken) {
            return false;
        }
        ensureUi();
        input();
        boolean press = action != Keys.RELEASE;
        if (!press && !gameKeys.remove(key) && open) {
            // The game never saw this press (the overlay took it), so it must not see the release.
            return true;
        }
        if (press && key == Keys.TAB && (mods & Keys.MOD_SHIFT) != 0
                && (mods & (Keys.MOD_CONTROL | Keys.MOD_ALT | Keys.MOD_SUPER)) == 0) {
            if (action == Keys.PRESS) {
                setOpen(!open);
            }
            return true;
        }
        if (press && key == Keys.F2 && (mods & Keys.MOD_SHIFT) != 0) {
            // Shift+F2 saves the replay; the game would take a screenshot on F2 alone.
            if (action == Keys.PRESS) {
                saveReplay();
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
        input();
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
        input();
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
            media.start();
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

    /** OpenGL: draws right before the window library presents the frame. */
    void glFrame() {
        if (!platform.hasGlContext()) {
            buildFrame(0, 0); // no OpenGL here (e.g. Vulkan); keep the clock and queues going
            return;
        }
        platform.sizes(sizes);
        captureFrame(sizes[0], sizes[1]);
        if (!buildFrame(sizes[0], sizes[1])) {
            return;
        }
        if (gl == null) {
            // Throws without a context made current by the game; caught by apply.
            GL.getCapabilities();
            gl = new GlRenderer();
            perfView.renderer = GL11.glGetString(GL11.GL_RENDERER) + " · OpenGL " + GL11.glGetString(GL11.GL_VERSION);
        }
        gl.draw(batch);
    }

    /** Reads the game's frame (before the overlay draws on it) into the replay buffer. */
    private void captureFrame(int width, int height) {
        if (!options.replay || captureBroken || width <= 0 || height <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (capture == null) {
            capture = new GlCapture();
        }
        if (!replay.due(now)) {
            return;
        }
        int w = Math.min(width, Replay.MAX_WIDTH) & ~1;
        int h = Math.max(2, Math.round(height * (w / (float) width))) & ~1;
        try {
            capture.capture(width, height, w, h, now, replay::add);
        } catch (RuntimeException | LinkageError e) {
            // The replay buffer is optional: turn it off, keep the overlay.
            captureBroken = true;
            report(e);
        }
    }

    /**
     * Runs once per frame on the render thread, whatever the graphics API: updates the frame clock,
     * runs posted work and, when anything is on screen, fills {@link #batch} for a target of
     * {@code width} x {@code height} pixels. Returns whether there is something to draw.
     */
    boolean buildFrame(int width, int height) {
        renderThread = Thread.currentThread();
        perf.frame();
        for (Runnable task; (task = renderQueue.poll()) != null; ) {
            task.run();
        }
        long now = System.currentTimeMillis();
        tickWorld(now);
        for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
            if (it.next().until < now) {
                it.remove();
            }
        }
        if (!open && !options.hud && toasts.isEmpty() && (cat == null || !cat.talking(now))) {
            return false; // nothing on screen, so the game's frame stays untouched
        }
        if (!platform.hasWindow()) {
            return false; // no window seen yet
        }
        platform.sizes(sizes);
        if (width <= 0 || height <= 0 || sizes[2] <= 0) {
            return false; // minimised, or no target
        }
        ensureUi();
        ui.platform = platform;
        pixelRatio = width / (float) sizes[2];
        // HiDPI: the larger of the OS scale and the framebuffer density, shrunk if the UI would not fit.
        float scale = Math.max(platform.contentScale(), pixelRatio);
        scale = Math.min(scale, Math.min(width / 860f, height / 480f));
        ui.scale = Math.max(0.75f, scale);
        if (atlas.full) {
            atlas.reset();
        }
        batch.begin(width, height);
        try {
            draw(width / ui.scale, height / ui.scale);
        } finally {
            batch.end();
            ui.endFrame();
        }
        return !batch.empty();
    }

    Batch batch() {
        return batch;
    }

    void setRendererName(String name) {
        perfView.renderer = name;
    }

    private void ensureUi() {
        if (ui != null) {
            return;
        }
        atlas = new Font.Atlas(batch);
        try {
            // LWJGL 2 ships no stb_truetype, so its games rasterise with java.awt.
            boolean awt = platform instanceof LegacyBackend;
            ui = new Ui(batch, font("Inter-Regular.ttf", awt), font("Inter-SemiBold.ttf", awt), platform);
            cat = new Cat();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Font font(String resource, boolean awt) throws IOException {
        return awt ? new AwtFont(resource, atlas) : new StbFont(resource, atlas);
    }

    private void draw(float w, float h) {
        if (open) {
            drawOverlay(w, h);
        } else if (options.hud) {
            perfView.drawHud(ui, w, h);
        }
        if (open) {
            // Sits in the strip under the content card, next to the close hint.
            cat.draw(ui, 16, h - 3, 0.75f, true, System.currentTimeMillis());
        } else if (options.cat) {
            cat.draw(ui, 16, h - 10, 1.5f, false, System.currentTimeMillis());
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
        // Narrow windows get tighter tabs so they stay clear of the brand and the clock.
        float tabPad = w < 1000 ? 18 : 32;
        for (int i = 0; i < TABS.length; i++) {
            widths[i] = ui.measure(ui.bold, 13, strings.get(TABS[i])) + tabPad;
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
        switch (tab) {
            case 0:
                chatView.draw(ui, x, y, innerW, innerH);
                break;
            case 1:
                notesView.draw(ui, x, y, innerW, innerH);
                break;
            case 2:
                toolsView.draw(ui, x, y, innerW, innerH);
                break;
            case 3:
                modsView.draw(ui, x, y, innerW, innerH);
                break;
            case 4:
                profileView.draw(ui, x, y, innerW, innerH);
                break;
            default:
                perfView.draw(ui, x, y, innerW, innerH);
                break;
        }

        String hint = strings.get("hint.close");
        ui.text(ui.regular, 12, (w - ui.measure(ui.regular, 12, hint)) / 2, h - 24, Ui.FAINT, hint);
        drawMedia(w, h);
    }

    /** What plays on the computer, with previous, play/pause and next, in the strip under the card. */
    private void drawMedia(float w, float h) {
        if (!media.active) {
            return;
        }
        float size = 24;
        float y = h - 30;
        float x = w - 18 - size * 3 - 8;
        String[] icons = {"«", media.playing ? "‖" : "▶", "»"};
        String[] commands = {"prev", "play", "next"};
        for (int i = 0; i < 3; i++) {
            float bx = x + i * (size + 4);
            boolean hot = ui.hover(bx, y, size, size);
            ui.round(bx, y, size, size, size / 2, hot ? Ui.HOVER : Ui.RAISED);
            float tw = ui.measure(ui.bold, 13, icons[i]);
            ui.text(ui.bold, 13, bx + (size - tw) / 2, y + (size - ui.lineHeight(ui.bold, 13)) / 2, Ui.FG, icons[i]);
            if (hot && ui.clickedIn(bx, y, size, size)) {
                media.command(commands[i]);
            }
        }
        String label = "♪ " + media.label();
        float labelW = Math.min(ui.measure(ui.regular, 12, label), Math.min(320, w * 0.3f));
        ui.textFit(ui.regular, 12, x - 10 - labelW, y + (size - ui.lineHeight(ui.regular, 12)) / 2, labelW,
                media.playing ? Ui.MUTED : Ui.FAINT, label);
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
