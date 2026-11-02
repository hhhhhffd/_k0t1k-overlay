package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.file.Files;
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
    private static final String[] TABS = {"tab.chat", "tab.browser", "tab.notes", "tab.tools", "tab.mods", "tab.profile", "tab.perf", "tab.settings"};
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
    /** Recording work (starting ffmpeg, downloading it, saving) takes long enough to keep off the io thread. */
    private final ScheduledExecutorService recordThread = Executors.newSingleThreadScheduledExecutor(daemon("record"));
    /** Filter list downloads wait on network timeouts, so they must not queue ahead of the browser and the other io work. */
    private final ExecutorService protectionThread = Executors.newSingleThreadExecutor(daemon("protection"));
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
    private final FakeHardware hardware;
    private final Stats stats;
    private final Timers timers;
    private final Screenshots screenshots;
    private final Mods mods;
    private final ToolsView toolsView;
    private final ModsView modsView;
    private final ProfileView profileView;
    private final SettingsView settingsView;
    private Cat cat;
    private final Media media;
    private final Ffmpeg ffmpeg;
    private final Recorder recorder;
    private GlCapture capture;
    /** Set when reading frames back failed; recording then stays off for this game. */
    private boolean captureBroken;
    /** Set while the settings wait for a key to bind; receives the combination, or 0 to cancel. */
    private java.util.function.IntConsumer keyCapture;
    private final CrashGuard crashGuard;
    private final Protection protection;
    private final Browser browser;
    private final BrowserView browserView;
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
    /** How far the tab strip is scrolled when it is too wide even with everything else hidden. */
    private float tabScroll;
    /** Ctrl+wheel movement not yet worth a scale step. */
    private float zoomWheel;
    /** What the interface scale is on automatic, in percent: the launcher's zoom or the screen's step. */
    private int autoPercent = 100;
    private final int[] sizes = new int[4];
    /** Framebuffer pixels per window coordinate, from the last frame; converts cursor positions. */
    private float pixelRatio = 1;
    /** Keys and buttons whose press reached the game, so only their releases are passed on while open. */
    private final java.util.Set<Integer> gameKeys = new java.util.HashSet<>();
    private final java.util.Set<Integer> gameButtons = new java.util.HashSet<>();

    private static final class Toast {
        final String title;
        final String body;
        long until;
        /** Set while the pointer is over it, so the time left stops running down. */
        boolean hovered;
        boolean dismissed;
        /** Where it was drawn last frame, {x, y, w, h}, or null; clicks are matched against it first. */
        float[] box;

        Toast(String title, String body, long until) {
            this.title = title;
            this.body = body;
            this.until = until;
        }
    }

    private final List<Toast> toasts = new ArrayList<>();
    private long lastToastTick;
    /** The pointer in window coordinates, as of its last move; negative until it moved. */
    private double pointerX = -1;
    private double pointerY = -1;
    /** The game's state this frame, which the auto-hide rules of the elements over the game look at. */
    private boolean gameMenu;
    private boolean gameF3;
    private boolean gameList;
    /** Whether the pointer was over a toast last frame; the toasts fade together. */
    private boolean toastsHovered;
    /** Whether the HUD is being placed: the overlay shows only the HUD and a hint. */
    private boolean hudEditing;

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
        perfView = new PerfView(perf, options);
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
        profileView = new ProfileView(stats, strings);
        settingsView = new SettingsView(this);
        hardware = new FakeHardware(this, options);
        media = new Media(ioThread, render, this::report);
        media.onTrack = track -> {
            if (!open) {
                toast(ToastKind.MUSIC, strings.get("media.now"), track);
            }
        };
        ffmpeg = new Ffmpeg(options, recordThread);
        recorder = new Recorder(options, ffmpeg, recordThread, render,
                (kind, title, body) -> render.execute(() -> toast(kind, title, body)), this::report);
        protection = new Protection(options, options.dataDir, io, protectionThread, this::report);
        browser = new Browser(options.dataDir, io, options.lang, protection);
        browserView = new BrowserView(browser, strings, this, io, protection);
        crashGuard = new CrashGuard(options.dataDir);
        crashGuard.install();
        chat.onNew = this::onChat;
        chat.start();
        notes.load();
        screenshots.start();
        watchTheme();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveOnExit, "k0t1k-overlay-exit"));
        toast(ToastKind.WELCOME, strings.get("welcome"), strings.get("welcome.body"));
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
                // The fake hardware of the F3 screen
                case "display":
                    return hardware.display(args);
                case "_getCpuInfo":
                case "glGetString":
                    return hardware.exit(method, args);
                // Links the game opens: the process that would open the system browser, or the SDL call
                case "start":
                case "browse":
                case "SDL_OpenURL":
                    return openedLink(method, args, exit);
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

    /** Set between the enter and exit hooks of a skipped SDL_OpenURL, so the game is told it worked. */
    private final ThreadLocal<Boolean> sdlOpened = new ThreadLocal<>();
    /** Set between the enter and exit hooks of a redirected ProcessBuilder.start: the builder and its original command. */
    private final ThreadLocal<Object[]> startedBuilder = new ThreadLocal<>();

    /**
     * ProcessBuilder.start, Desktop.browse and SDL_OpenURL: when the game opens a web address and the
     * player wants those in the overlay, the launch is made harmless and the address is opened in a tab.
     * Never touches the overlay's own launches ({@link Links#OWN}) or anything that is not a web address.
     */
    @SuppressWarnings("unchecked") // the saved command is the List<String> stored by the same method
    private Object openedLink(String method, Object[] args, boolean exit) {
        if (exit && method.equals("start")) {
            // The caller may reuse its builder; give it back the command that was swapped out.
            Object[] saved = startedBuilder.get();
            startedBuilder.remove();
            if (saved != null && saved[0] == args[0]) {
                ((ProcessBuilder) saved[0]).command((List<String>) saved[1]);
            }
            return null;
        }
        if (exit) {
            Boolean opened = sdlOpened.get();
            sdlOpened.remove();
            return opened;
        }
        if (!options.linksInOverlay || Links.OWN.get() || !linksUsable()) {
            return null;
        }
        String url;
        switch (method) {
            case "start":
                url = Links.openedAddress(((ProcessBuilder) args[0]).command());
                if (url != null) {
                    startedBuilder.set(new Object[] {args[0], new ArrayList<>(((ProcessBuilder) args[0]).command())});
                    ((ProcessBuilder) args[0]).command(Links.harmless());
                }
                break;
            case "browse":
                url = Links.webAddress(String.valueOf(args[0]));
                break;
            default:
                url = Links.webAddress(Links.text(args[0]));
                break;
        }
        if (url == null) {
            return null;
        }
        render.execute(() -> openLink(url));
        if (method.equals("SDL_OpenURL")) {
            sdlOpened.set(Boolean.TRUE);
        }
        return method.equals("start") ? null : Boolean.TRUE;
    }

    /** The browser is installed (or running) and the renderer can draw its pictures. */
    private boolean linksUsable() {
        return imagesSupported() && (browser.state == Browser.State.READY || browser.state == Browser.State.STARTING);
    }

    /** Shows the Browser tab with {@code url} in a new tab of its own. */
    private void openLink(String url) {
        tab = 1;
        ui.focus = null;
        setOpen(true);
        browserView.openExternal(url);
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

    /**
     * Applies the launcher's theme (see {@link Theme}) when the overlay starts and whenever the file
     * changes. The check is a timestamp comparison on the io thread every two seconds; the new palette
     * is swapped in on the render thread.
     */
    private void watchTheme() {
        Path file = options.dataDir.resolve(Theme.FILE);
        long[] seen = {-1};
        ioThread.scheduleWithFixedDelay(() -> {
            try {
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (modified == seen[0]) {
                    return;
                }
                seen[0] = modified;
                Theme theme = Theme.load(file);
                if (theme == null) {
                    log("ignoring an invalid " + Theme.FILE + "; the previous colours stay");
                    return;
                }
                render.execute(() -> Ui.apply(theme));
            } catch (java.nio.file.NoSuchFileException e) {
                // no theme from the launcher (yet): the default palette stays
            } catch (IOException | RuntimeException e) {
                report(e);
            }
        }, 0, 2, java.util.concurrent.TimeUnit.SECONDS);
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
        browserView.saveOnExit();
        chat.pollNow();
        notes.saveNow();
        media.stop();
        recorder.shutdown();
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

    /** Saves the replay buffer (the save hotkey or the tools tab). */
    void saveReplay() {
        if (captureSupported()) {
            recorder.saveReplay();
        }
    }

    /** Starts or stops a recording (the record hotkey or the tools tab). */
    void toggleRecording() {
        if (captureSupported()) {
            recorder.toggleRecording();
        }
    }

    /** Whether this game's renderer can give frames to the recorder; says why not when it cannot. */
    boolean captureSupported() {
        if (!captureAvailable()) {
            toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get(legacyRenderer() ? "rec.legacy" : "rec.unsupported"));
            return false;
        }
        return true;
    }

    /** Whether the renderer wants frames at all; cheaper than {@link #replayDue}. */
    boolean replayWanted() {
        return !captureBroken && recorder.wanted();
    }

    /** Vulkan: whether to read back this frame for the recorder; when true the frame is claimed. */
    boolean replayDue(int width, int height) {
        return replayWanted() && recorder.due(width, height);
    }

    /** Vulkan: reading frames back failed; recording stays off for this game, the overlay stays. */
    void replayFailed(Throwable error) {
        captureBroken = true;
        recorder.failed(error);
        toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.unsupported"));
    }

    Recorder recorder() {
        return recorder;
    }

    Ffmpeg ffmpeg() {
        return ffmpeg;
    }

    /** Whether the game renders in a way recording can read, for the tools tab. */
    boolean captureAvailable() {
        return !captureBroken && !legacyRenderer();
    }

    boolean legacyRenderer() {
        return platform instanceof LegacyBackend;
    }

    /** The next key press, with its modifiers, is handed to {@code receiver} as a combination instead of being used. */
    void captureKey(java.util.function.IntConsumer receiver) {
        keyCapture = receiver;
    }

    /** Drops a pending key capture, e.g. when its row is no longer on screen. */
    void cancelKeyCapture() {
        keyCapture = null;
    }

    boolean capturingKey() {
        return keyCapture != null;
    }

    /** The interface scale on automatic, in percent. */
    int autoScalePercent() {
        return autoPercent;
    }

    /** Sets the interface scale in percent, {@link Options#MIN_SCALE} to {@link Options#MAX_SCALE}; 0 is automatic. */
    void setUiScale(int percent) {
        if (percent != options.uiScale) {
            options.uiScale = percent;
            saveSettings();
        }
    }

    Options options() {
        return options;
    }

    /** Deletes the browser's cookies (logins); false when the browser is not running. */
    boolean clearBrowserCookies() {
        return browser.clearCookies();
    }

    /** Finds the browsers and profiles "Import sign-ins" can use, on the io thread; answers on the render thread. */
    void detectBrowserLogins(java.util.function.Consumer<java.util.List<BrowserLogins.Source>> onDone) {
        io.execute(() -> {
            java.util.List<BrowserLogins.Source> sources = BrowserLogins.detect();
            render.execute(() -> onDone.accept(sources));
        });
    }

    /**
     * Imports {@code source}/{@code profile}'s allowlisted cookies into the browser tab and calls
     * {@code onDone} on the render thread with a one-line result. Runs on the io thread.
     */
    void importBrowserLogins(BrowserLogins.Source source, BrowserLogins.Profile profile, java.util.function.Consumer<String> onDone) {
        io.execute(() -> {
            String message;
            try {
                java.util.Set<String> allowlist = BrowserLogins.parseAllowlist(options.browserImportAllowlist);
                java.util.List<BrowserLogins.Cookie> cookies = new java.util.ArrayList<>();
                BrowserLogins.Result result = BrowserLogins.importCookies(source, profile, allowlist, cookies);
                message = browserLoginMessage(source, profile, cookies, result);
            } catch (RuntimeException | LinkageError e) {
                report(e);
                message = strings.format("settings.browserImportErrorGeneric", e.getClass().getSimpleName());
            }
            String completedMessage = message;
            render.execute(() -> onDone.accept(completedMessage));
        });
    }

    /** Writes the imported cookies into the browser tab (io thread) and composes the result line. */
    private String browserLoginMessage(BrowserLogins.Source source, BrowserLogins.Profile profile,
            java.util.List<BrowserLogins.Cookie> cookies, BrowserLogins.Result result) {
        BrowserEngine engine = browser.engine();
        if (engine == null) {
            return strings.get("settings.browserImportNoTab");
        }
        if (cookies.isEmpty()) {
            return "locked".equals(result.error) ? strings.format("settings.browserImportLocked", source.displayName)
                    : result.error != null ? strings.format("settings.browserImportErrorGeneric", result.error)
                    : strings.format("settings.browserImportDone", 0, source.displayName, profile.name);
        }
        int written = 0;
        for (BrowserLogins.Cookie cookie : cookies) {
            String host = cookie.host.startsWith(".") ? cookie.host.substring(1) : cookie.host;
            String url = (cookie.secure ? "https://" : "http://") + host + cookie.path;
            String domain = cookie.host.startsWith(".") ? cookie.host : "";
            if (engine.setCookie(url, cookie.name, cookie.value, domain, cookie.path, cookie.secure,
                    cookie.httpOnly, cookie.expiresMillis)) {
                written++;
            }
        }
        engine.flushCookies();
        render.execute(browserView::reloadActive);
        String message = strings.format("settings.browserImportDone", written, source.displayName, profile.name);
        if (result.undecryptable > 0) {
            message += " " + strings.format("settings.browserImportSkipped", result.undecryptable);
        }
        return message;
    }

    Protection protection() {
        return protection;
    }

    FakeHardware hardware() {
        return hardware;
    }

    void saveSettings() {
        io.execute(options.saveTask());
    }

    /** Switches to placing the HUD; Esc, Enter or the Done button leads back to the settings. */
    void startHudEdit() {
        hudEditing = true;
        ui.resetInput();
    }

    private void endHudEdit() {
        hudEditing = false;
        perfView.endEdit();
        ui.resetInput();
        saveSettings();
    }

    Media media() {
        return media;
    }

    /** Whether the renderer can draw pictures (the browser's page): OpenGL 3.2 and Vulkan, not LWJGL 2's. */
    boolean imagesSupported() {
        return gl != null || vk != null;
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

    void toast(ToastKind kind, String title, String body) {
        long millis = options.toastMillis(kind);
        if (millis == 0) {
            return;
        }
        toasts.add(new Toast(title, body, System.currentTimeMillis() + millis));
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
            toast(ToastKind.MENTION, strings.get("chat.mention"), line.text);
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
                // The first time the name appears decides: when a message separator follows it, it is the
                // author's name in the server's chat format ("[Rank] Name: ...", "Name » ..."), so the line is
                // the player's own message.
                return !followedBySeparator(text, end);
            }
            at = text.indexOf(lower, at + 1);
        }
        return false;
    }

    /** Whether the first non-space character at or after {@code from} separates a chat author from the message. */
    private static boolean followedBySeparator(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        return i < text.length() && ":>»›→➤|".indexOf(text.charAt(i)) >= 0;
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
        toast(ToastKind.ACHIEVEMENT, strings.get("ach.unlocked"), strings.get("ach." + id));
        catSays("cat.achievement");
    }

    private void timerFired(Timers.Timer timer) {
        stats.add("timers", 1);
        toast(ToastKind.TIMER, strings.get("tools.timerDone"), timer.label);
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
        toast(ToastKind.SCREENSHOT, strings.get("shot.saved"), body);
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

    /**
     * GLFW on X11 reports the modifiers from before the event, so a modifier key's own press (or
     * release) is missing from its {@code mods}; without this Ctrl+wheel would lag one key behind.
     */
    private static int withModifierKey(int key, int action, int mods) {
        int bit;
        switch (key) {
            case 340: case 344: bit = Keys.MOD_SHIFT; break;
            case 341: case 345: bit = Keys.MOD_CONTROL; break;
            case 342: case 346: bit = Keys.MOD_ALT; break;
            case 343: case 347: bit = Keys.MOD_SUPER; break;
            default: return mods;
        }
        return action == Keys.RELEASE ? mods & ~bit : mods | bit;
    }

    boolean key(int key, int action, int mods) {
        if (broken) {
            return false;
        }
        ensureUi();
        input();
        ui.mods = withModifierKey(key, action, mods);
        boolean press = action != Keys.RELEASE;
        if (!press && !gameKeys.remove(key) && open) {
            // The game never saw this press (the overlay took it), so it must not see the release.
            return true;
        }
        if (press && keyCapture != null) {
            if (action == Keys.PRESS && !Hotkeys.isModifier(key)) {
                if (key == Keys.ESCAPE) {
                    keyCapture.accept(0);
                    keyCapture = null;
                } else if (Hotkeys.allowed(key, mods)) {
                    keyCapture.accept(Hotkeys.of(key, mods));
                    keyCapture = null;
                } else {
                    toast(ToastKind.OTHER, strings.get("hotkeys.title"), strings.get("hotkeys.needMod"));
                }
            }
            return true;
        }
        if (press && Hotkeys.matches(options.hotkeyOpen, key, mods)) {
            if (action == Keys.PRESS) {
                setOpen(!open);
            }
            return true;
        }
        if (press && Hotkeys.matches(options.hotkeySave, key, mods)) {
            // The game would take a screenshot on F2 alone, so the hotkey is not passed on.
            if (action == Keys.PRESS) {
                saveReplay();
            }
            return true;
        }
        if (press && Hotkeys.matches(options.hotkeyRecord, key, mods)) {
            if (action == Keys.PRESS) {
                toggleRecording();
            }
            return true;
        }
        if (!open) {
            hardware.key(key, action);
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
        if (hudEditing && (key == Keys.ESCAPE || key == Keys.ENTER || key == Keys.KP_ENTER)) {
            endHudEdit();
        } else if (key == Keys.ESCAPE && !(tab == 1 && browserView.wantsEscape())) {
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
        // Kept while closed too: the elements over the game fade when the pointer is over them.
        pointerX = x;
        pointerY = y;
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
        keyCapture = null;
        if (value) {
            media.start();
            open = true;
            platform.showCursor();
            platform.sizes(sizes);
            ui.onMove(sizes[2] / 2f * pixelRatio / ui.scale, sizes[3] / 2f * pixelRatio / ui.scale);
            toasts.clear();
        } else {
            settingsView.flush();
            browserView.flushSession();
            browserView.hide();
            if (hudEditing) {
                endHudEdit();
            }
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
            hardware.bypass = true;
            try {
                perfView.renderer = GL11.glGetString(GL11.GL_RENDERER) + " · OpenGL " + GL11.glGetString(GL11.GL_VERSION);
            } finally {
                hardware.bypass = false;
            }
        }
        gl.draw(batch);
    }

    /** Reads the game's frame (before the overlay draws on it) for the recorder. */
    private void captureFrame(int width, int height) {
        if (captureBroken || width <= 0 || height <= 0) {
            return;
        }
        if (!recorder.wanted()) {
            if (capture != null) {
                capture.release();
            }
            return;
        }
        try {
            if (capture == null) {
                capture = new GlCapture();
            }
            if (recorder.due(width, height)) {
                capture.capture(width, height, recorder);
            }
        } catch (RuntimeException | LinkageError e) {
            // Recording is optional: turn it off, keep the overlay.
            replayFailed(e);
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
        if (captureAvailable()) {
            recorder.tick();
        }
        for (Iterator<Toast> it = toasts.iterator(); it.hasNext(); ) {
            Toast toast = it.next();
            if (toast.hovered) {
                toast.until += now - lastToastTick;
                toast.hovered = false;
            }
            if (toast.dismissed || toast.until < now) {
                it.remove();
            }
        }
        lastToastTick = now;
        if (!open && !options.hud && toasts.isEmpty() && !recorder.recording() && (cat == null || !cat.talking(now))) {
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
        // Framebuffer pixels per logical unit of the OS: its content scale, or the framebuffer
        // density where the window is counted in points (macOS, Wayland).
        float density = Math.max(platform.contentScale(), pixelRatio);
        // The launcher's page is zoomed by its setting, or by a step chosen from its screen width in
        // logical pixels; automatic follows it so both look the same size.
        autoPercent = options.launcherScale > 0 ? options.launcherScale
                : Options.autoScale(platform.monitorWidth() / platform.contentScale());
        float scale = density * (options.uiScale > 0 ? options.uiScale : autoPercent) / 100f;
        // Only a window too small for the interface shrinks it; anything bigger keeps the chosen size.
        float fit = Math.min(width / 640f, height / 360f);
        if (scale > fit) {
            scale = Math.max(fit, 0.5f);
        }
        if (scale != ui.scale) {
            // Keep the pointer over the same spot of the screen.
            ui.mouseX *= ui.scale / scale;
            ui.mouseY *= ui.scale / scale;
            ui.scale = scale;
        }
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
        // Toasts are drawn on top but after the tabs' widgets, so a click on one is taken here, before
        // anything underneath could react to it.
        if (open && ui.clicked) {
            for (Toast toast : toasts) {
                float[] b = toast.box;
                if (b != null && ui.mouseX >= b[0] && ui.mouseX < b[0] + b[2] && ui.mouseY >= b[1] && ui.mouseY < b[1] + b[3]) {
                    toast.dismissed = true;
                    ui.clicked = false;
                    break;
                }
            }
        }
        if (open && hudEditing) {
            if (perfView.edit(ui, w, h)) {
                endHudEdit();
            }
        } else if (open) {
            drawOverlay(w, h);
        }
        long now = System.currentTimeMillis();
        gameMenu = !open && platform.gameCursorVisible();
        gameF3 = hardware.f3Shown();
        gameList = gameKeys.contains(Keys.TAB);
        if (!open && options.hud) {
            float[] hudBox = new float[4];
            perfView.hudRect(w, h, hudBox);
            batch.alpha = fade(options.hudHide, hudBox, now);
            perfView.drawHud(ui, w, h);
            batch.alpha = 1;
        }
        // Where the cat is drawn: in the strip under the content card while open, else in the corner.
        float catBottom = open ? h - 3 : h - 10;
        float catPixel = open ? 0.75f : 1.5f;
        boolean catShown = open || options.cat;
        float[] catBox = catShown ? cat.bounds(ui, 16, catBottom, catPixel, open, now) : null;
        if (catShown) {
            batch.alpha = fade(options.catHide, catBox, now);
            cat.draw(ui, 16, catBottom, catPixel, open, now);
            batch.alpha = 1;
        }
        float[] recBox = recorder.indicatorBox(ui, w);
        if (recBox != null) {
            batch.alpha = fade(options.recHide, recBox, now);
            recorder.drawIndicator(ui, recBox);
            batch.alpha = 1;
        }
        drawToasts(w, h, catBox, now);
    }

    /** Whether the pointer is over the box {x, y, width, height} of an element that is drawn over the game. */
    private boolean pointerOver(float[] box) {
        if (box == null || !gameMenu || pointerX < 0) {
            return false;
        }
        float x = (float) (pointerX * pixelRatio / ui.scale);
        float y = (float) (pointerY * pixelRatio / ui.scale);
        return x >= box[0] && x < box[0] + box[2] && y >= box[1] && y < box[1] + box[3];
    }

    /** The alpha for an element over the game under its auto-hide rules; 1 while the overlay is open. */
    private float fade(AutoHide hide, float[] box, long now) {
        return open ? 1 : hide.alpha(pointerOver(box), gameMenu, gameF3, gameList, now);
    }

    private void drawOverlay(float w, float h) {
        ui.rect(0, 0, w, h, Ui.VEIL);

        // Top bar: brand, tabs, clock.
        float barH = 48;
        ui.rect(0, 0, w, barH, Ui.CARD);
        ui.rect(0, barH, w, 1, Ui.LINE);
        String clock = LocalTime.now().format(CLOCK);
        long minutes = (System.currentTimeMillis() - started) / 60000;
        String session = strings.format("session", String.format("%d:%02d", minutes / 60, minutes % 60));
        float clockW = ui.measure(ui.bold, 16, clock);
        float sessionW = ui.measure(ui.regular, 11, session);
        float brandW = ui.measure(ui.bold, 16, "_k0t1k");
        float subtitleW = 6 + ui.measure(ui.regular, 13, "overlay");

        float labelsW = 0;
        for (String tabKey : TABS) {
            labelsW += ui.measure(ui.bold, 13, strings.get(tabKey).toUpperCase(Locale.ROOT));
        }
        // Fit the tabs between the brand and the clock: tighter padding first, then drop the
        // subtitle and the session time, then the clock; a strip still too wide scrolls.
        int stage = 0;
        float tabPad;
        float left;
        float right;
        float stripW;
        while (true) {
            tabPad = stage == 0 ? 32 : stage == 1 ? 18 : 10;
            left = 18 + brandW + (stage < 3 ? subtitleW : 0) + 16;
            right = w - (stage < 4 ? 18 + Math.max(clockW, stage < 3 ? sessionW : 0) + 16 : 18);
            stripW = labelsW + TABS.length * tabPad;
            if (stripW <= right - left || stage == 4) {
                break;
            }
            stage++;
        }

        float bx = 18;
        bx += ui.text(ui.bold, 16, bx, (barH - ui.lineHeight(ui.bold, 16)) / 2, Ui.ACCENT, "_k0t1k");
        if (stage < 3) {
            ui.text(ui.regular, 13, bx + 6, (barH - ui.lineHeight(ui.regular, 13)) / 2 + 1, Ui.FAINT, "overlay");
        }
        if (stage < 4) {
            ui.text(ui.bold, 16, w - 18 - clockW, 7, Ui.FG, clock);
            if (stage < 3) {
                ui.text(ui.regular, 11, w - 18 - sessionW, 27, Ui.FAINT, session);
            }
        }

        float room = right - left;
        float tx;
        if (stripW <= room) {
            tabScroll = 0;
            // Centred on the screen unless the brand or the clock is in the way.
            tx = Math.max(left, Math.min((w - stripW) / 2, right - stripW));
        } else {
            tabScroll = Math.max(0, Math.min(tabScroll - ui.takeScroll(left, 0, room, barH) * 40, stripW - room));
            tx = left - tabScroll;
            ui.clip(left, 0, room, barH);
        }
        for (int i = 0; i < TABS.length; i++) {
            String label = strings.get(TABS[i]).toUpperCase(Locale.ROOT);
            float tabW = ui.measure(ui.bold, 13, label) + tabPad;
            if (tx + tabW > left && tx < right && ui.tab(tx, 0, tabW, barH, label, tab == i)) {
                tab = i;
                ui.focus = null;
                keyCapture = null;
            }
            tx += tabW;
        }
        if (stripW > room) {
            ui.unclip();
        }

        // Content card.
        float cardW = Math.min(w - 32, 1120);
        float cx = (w - cardW) / 2;
        float cy = barH + 16;
        float cardH = h - cy - 34;
        ui.panel(cx, cy, cardW, cardH, 12, Ui.CARD, Ui.LINE);
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
                browserView.draw(ui, x, y, innerW, innerH);
                break;
            case 2:
                notesView.draw(ui, x, y, innerW, innerH);
                break;
            case 3:
                toolsView.draw(ui, x, y, innerW, innerH);
                break;
            case 4:
                modsView.draw(ui, x, y, innerW, innerH);
                break;
            case 5:
                profileView.draw(ui, x, y, innerW, innerH);
                break;
            case 6:
                perfView.draw(ui, x, y, innerW, innerH);
                break;
            default:
                settingsView.draw(ui, x, y, innerW, innerH);
                break;
        }
        zoomShortcuts();

        String hint = strings.get("hint.close");
        ui.text(ui.regular, 12, (w - ui.measure(ui.regular, 12, hint)) / 2, h - 24, Ui.FAINT, hint);
        drawMedia(w, h);
    }

    /**
     * Ctrl+wheel and Ctrl with plus, minus or 0 change the interface scale. It runs after the tab drew,
     * so content that uses these itself (the browser page) takes the input first: wheel movement it
     * took is gone, and a key it took must be removed from {@code ui.keys}.
     */
    private void zoomShortcuts() {
        if (Ui.shortcut(ui.mods)) {
            zoomWheel += ui.scrollY;
            ui.scrollY = 0;
        } else {
            zoomWheel = 0;
        }
        while (Math.abs(zoomWheel) >= 1) {
            zoom(zoomWheel > 0 ? 10 : -10);
            zoomWheel -= Math.signum(zoomWheel);
        }
        for (int[] k : ui.keys) {
            if (!Ui.shortcut(k[1])) {
                continue;
            }
            switch (k[0]) {
                case Keys.EQUAL:
                case Keys.KP_ADD:
                    zoom(10);
                    break;
                case Keys.MINUS:
                case Keys.KP_SUBTRACT:
                    zoom(-10);
                    break;
                case Keys.ZERO:
                case Keys.KP_0:
                    setUiScale(0);
                    break;
                default:
                    break;
            }
        }
    }

    private void zoom(int delta) {
        int current = options.uiScale > 0 ? options.uiScale : autoPercent;
        setUiScale(Math.max(Options.MIN_SCALE, Math.min(Options.MAX_SCALE, current + delta)));
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

    /**
     * Stacks the toasts at the chosen place, newest nearest to the anchor. A toast that would cover
     * the HUD or the cat (with its bubble) moves on along the stack until it clears them.
     */
    private void drawToasts(float w, float h, float[] catBox, long now) {
        int pos = options.toastPos;
        boolean up = pos == Options.TOAST_BOTTOM_LEFT || pos == Options.TOAST_BOTTOM_RIGHT;
        boolean left = pos == Options.TOAST_TOP_LEFT || pos == Options.TOAST_BOTTOM_LEFT;
        float tw = Math.min(w, pos == Options.TOAST_CENTER ? 240 : 300);
        float x = pos == Options.TOAST_CENTER ? (w - tw) / 2 : left ? 16 : w - tw - 16;
        x = Math.max(0, Math.min(x, w - tw));
        float y = up ? h - 16 : pos == Options.TOAST_CENTER ? h / 2 + 48 : 16;
        List<float[]> avoid = new ArrayList<>();
        if (!open && options.hud) {
            float[] hudBox = new float[4];
            perfView.hudRect(w, h, hudBox);
            avoid.add(hudBox);
        }
        if (catBox != null) {
            avoid.add(catBox);
        }
        float gap = 10;
        boolean hovered = false;
        batch.alpha = open ? 1 : options.toastHide.alpha(toastsHovered, gameMenu, gameF3, gameList, now);
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast toast = toasts.get(i);
            List<int[]> lines = ui.wrap(ui.regular, 13, toast.body, tw - 28);
            int shown = Math.min(lines.size(), 3);
            float th = 38 + shown * ui.lineHeight(ui.regular, 13);
            float maxY = Math.max(0, h - th);
            float startY = Math.max(0, Math.min(up ? y - th : y, maxY));
            float ty = startY;
            for (int attempt = 0; attempt < 2; attempt++) {
                boolean moveUp = attempt == 0 ? up : !up;
                ty = startY;
                for (boolean moved = true; moved; ) {
                    moved = false;
                    for (float[] box : avoid) {
                        if (x < box[0] + box[2] + gap && x + tw > box[0] - gap
                                && ty < box[1] + box[3] + gap && ty + th > box[1] - gap) {
                            float nextY = moveUp ? box[1] - gap - th : box[1] + box[3] + gap;
                            if (moveUp ? nextY < ty : nextY > ty) {
                                ty = nextY;
                                moved = true;
                            }
                        }
                    }
                }
                if (ty >= 0 && ty <= maxY) {
                    break;
                }
            }
            // When neither side has room, overlap the obstacle rather than leave the screen.
            ty = Math.max(0, Math.min(ty, maxY));
            // Pointer interaction only exists while the overlay is open.
            if (open && ui.hover(x, ty, tw, th)) {
                toast.hovered = true;
            }
            toast.box = new float[] {x, ty, tw, th};
            hovered |= pointerOver(toast.box);
            ui.panel(x, ty, tw, th, 10, Ui.POPUP, Ui.LINE_HOVER);
            ui.round(x + 1, ty + 10, 3, th - 20, 1.5f, Ui.ACCENT);
            ui.text(ui.bold, 13, x + 14, ty + 10, Ui.FG, toast.title);
            for (int l = 0; l < shown; l++) {
                int[] span = lines.get(l);
                ui.text(ui.regular, 13, x + 14, ty + 30 + l * ui.lineHeight(ui.regular, 13), Ui.MUTED,
                        toast.body, span[0], span[1]);
            }
            y = up ? ty - gap : ty + th + gap;
        }
        toastsHovered = hovered;
        batch.alpha = 1;
    }
}
