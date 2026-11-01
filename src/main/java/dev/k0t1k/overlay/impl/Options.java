package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Start-up options and the settings the player changes in the overlay.
 *
 * <p>Options come from the agent argument ({@code -javaagent:overlay.jar=dataDir=...,lang=ru}) and
 * can be overridden by system properties ({@code -Dk0t1k.overlay.dataDir=...}), which suit paths
 * with commas. Known options:
 * <ul>
 *   <li>{@code dataDir}: where history, notes and settings live; default {@code <gameDir>/k0t1k-overlay}
 *   <li>{@code gameDir}: the game folder; default the working directory, which launchers set to it
 *   <li>{@code lang}: {@code en} (default) or {@code ru}
 *   <li>{@code crashDialog}: {@code false} turns off the overlay's own crash window
 *   <li>{@code scale}: the launcher's interface zoom in percent ({@value #MIN_SCALE} to
 *       {@value #MAX_LAUNCHER_SCALE}). The overlay's automatic interface scale follows it, as the launcher's
 *       does; absent or invalid means the launcher is on automatic, which grows with the screen width
 * </ul>
 */
final class Options {
    /** The range of the player's interface scale, in percent. */
    static final int MIN_SCALE = 50;
    static final int MAX_SCALE = 200;
    static final int MAX_LAUNCHER_SCALE = 300;
    static final int MIN_HUD_SIZE = 50;
    static final int MAX_HUD_SIZE = 200;
    static final int MIN_HUD_OPACITY = 20;
    static final int TOAST_CENTER = 0;
    static final int TOAST_TOP_LEFT = 1;
    static final int TOAST_TOP_RIGHT = 2;
    static final int TOAST_BOTTOM_LEFT = 3;
    static final int TOAST_BOTTOM_RIGHT = 4;

    final Path gameDir;
    final Path dataDir;
    final Strings strings;
    /** The player's name, for highlighting mentions; empty when unknown. */
    final String username;

    // Saved settings.
    boolean hud;
    /**
     * Where the HUD sits, as a share (0 to 1) of the screen space it can move in, so the place
     * survives a change of resolution: 0, 0 is the top left corner, 1, 1 the bottom right.
     */
    float hudX;
    float hudY;
    /** The HUD's size in percent on top of the interface scale, {@link #MIN_HUD_SIZE} to {@link #MAX_HUD_SIZE}. */
    int hudSize = 100;
    /** How opaque the HUD's background is, in percent. */
    int hudOpacity = 100;
    boolean hudFps = true;
    boolean hudFrame = true;
    boolean hudLow;
    boolean hudHeap = true;
    boolean hudCpu;
    boolean hudGc;
    boolean hudGraph = true;
    /** Where toasts appear: one of the {@code TOAST_*} constants. */
    int toastPos = TOAST_BOTTOM_RIGHT;
    /** Whether the cat pops up to react to deaths, advancements, AFK and timers. */
    boolean cat = true;
    /** Whether the last seconds of the game are kept for Shift+F2 (OpenGL 3.2 and Vulkan). */
    boolean replay = true;
    /** "video" (MP4 with ffmpeg, otherwise AVI) or "gif". */
    String replayFormat = "video";
    /** The interface scale in percent, {@link #MIN_SCALE} to {@link #MAX_SCALE}, or 0 for automatic. */
    int uiScale;
    /** The browser's home page and new-tab page. */
    String browserHome = "https://www.google.com/";
    /** The search engine for the address bar: a key of {@code BrowserView.ENGINES}. */
    String browserSearch = "google";
    boolean bookmarksBar = true;
    /** The launcher's interface zoom in percent, or 0 when it is on automatic. */
    final int launcherScale;
    /** Whether the F3 screen shows the fake hardware below instead of the real one. */
    boolean fakeHardware;
    String fakeCpu = "Intel Core i9-14900K";
    int fakeThreads = 32;
    String fakeGpu = "NVIDIA GeForce RTX 8080";
    String fakeVendor = "NVIDIA Corporation";
    /** Maximum memory in MiB, shown on Minecraft 26.x only. */
    int fakeMemory = 65536;
    /** Whether the overlay shows its own crash window; a launcher with its own can turn it off. */
    final boolean crashDialog;
    final String lang;

    private final Path settingsFile;

    Options(String agentArgs) {
        Properties args = new Properties();
        for (String pair : agentArgs.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                args.setProperty(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        gameDir = Paths.get(option(args, "gameDir", System.getProperty("user.dir"))).toAbsolutePath();
        dataDir = Paths.get(option(args, "dataDir", gameDir.resolve("k0t1k-overlay").toString())).toAbsolutePath();
        lang = option(args, "lang", "en");
        strings = new Strings(lang);
        crashDialog = !"false".equalsIgnoreCase(option(args, "crashDialog", "true"));
        launcherScale = parseScale(option(args, "scale", ""), MAX_LAUNCHER_SCALE);
        username = usernameFromCommandLine();
        settingsFile = dataDir.resolve("overlay.properties");
    }

    private static String option(Properties args, String key, String fallback) {
        String value = System.getProperty("k0t1k.overlay." + key);
        if (value == null || value.isEmpty()) {
            value = args.getProperty(key);
        }
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** The percent in {@code text}, or 0 when it is not a number from {@link #MIN_SCALE} to {@code max}. */
    private static int parseScale(String text, int max) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= MIN_SCALE && value <= max ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * What the launcher's interface zoom is when its setting is unset (apps/app autoScale): by the
     * screen width in logical pixels, which the operating system's scale has already divided out.
     */
    static int autoScale(float logicalWidth) {
        return logicalWidth >= 3400 ? 150 : logicalWidth >= 2500 ? 125 : 100;
    }

    /** {@code --username} from the game's arguments; player names never contain spaces. */
    private static String usernameFromCommandLine() {
        String command = System.getProperty("sun.java.command", "");
        String[] parts = command.split(" ");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("--username".equals(parts[i])) {
                return parts[i + 1];
            }
        }
        return "";
    }

    void loadSettings() throws IOException {
        Properties saved = new Properties();
        if (Files.exists(settingsFile)) {
            try (InputStream in = Files.newInputStream(settingsFile)) {
                saved.load(in);
            }
        }
        hud = Boolean.parseBoolean(saved.getProperty("hud", "false"));
        cat = Boolean.parseBoolean(saved.getProperty("cat", "true"));
        replay = Boolean.parseBoolean(saved.getProperty("replay", "true"));
        replayFormat = "gif".equals(saved.getProperty("replayFormat")) ? "gif" : "video";
        uiScale = parseScale(saved.getProperty("uiScale", "0"), MAX_SCALE);
        hudSize = intSetting(saved, "hudSize", 100, MIN_HUD_SIZE, MAX_HUD_SIZE);
        hudOpacity = intSetting(saved, "hudOpacity", 100, MIN_HUD_OPACITY, 100);
        toastPos = intSetting(saved, "toastPos", TOAST_BOTTOM_RIGHT, TOAST_CENTER, TOAST_BOTTOM_RIGHT);
        hudFps = Boolean.parseBoolean(saved.getProperty("hudFps", "true"));
        hudFrame = Boolean.parseBoolean(saved.getProperty("hudFrame", "true"));
        hudLow = Boolean.parseBoolean(saved.getProperty("hudLow", "false"));
        hudHeap = Boolean.parseBoolean(saved.getProperty("hudHeap", "true"));
        hudCpu = Boolean.parseBoolean(saved.getProperty("hudCpu", "false"));
        hudGc = Boolean.parseBoolean(saved.getProperty("hudGc", "false"));
        hudGraph = Boolean.parseBoolean(saved.getProperty("hudGraph", "true"));
        if (saved.containsKey("hudX")) {
            hudX = floatSetting(saved, "hudX");
            hudY = floatSetting(saved, "hudY");
        } else {
            // Older files stored a corner: 0 top left, 1 top right, 2 bottom left, 3 bottom right.
            int corner = intSetting(saved, "hudCorner", 0, 0, 3);
            hudX = corner % 2;
            hudY = corner / 2;
        }
        browserHome = saved.getProperty("browserHome", browserHome);
        browserSearch = saved.getProperty("browserSearch", browserSearch);
        bookmarksBar = Boolean.parseBoolean(saved.getProperty("bookmarksBar", "true"));
        fakeHardware = Boolean.parseBoolean(saved.getProperty("fakeHardware", "false"));
        fakeCpu = saved.getProperty("fakeCpu", fakeCpu);
        fakeGpu = saved.getProperty("fakeGpu", fakeGpu);
        fakeVendor = saved.getProperty("fakeVendor", fakeVendor);
        fakeThreads = number(saved, "fakeThreads", fakeThreads);
        fakeMemory = number(saved, "fakeMemory", fakeMemory);
    }

    private static int intSetting(Properties saved, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(saved.getProperty(key, Integer.toString(fallback)))));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float floatSetting(Properties saved, String key) {
        try {
            float value = Float.parseFloat(saved.getProperty(key, "0"));
            return Float.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int number(Properties saved, String key, int fallback) {
        try {
            return Integer.parseInt(saved.getProperty(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Runs on the io thread; takes a snapshot so the render thread can keep changing the fields. */
    Runnable saveTask() {
        Properties snapshot = new Properties();
        snapshot.setProperty("hud", Boolean.toString(hud));
        snapshot.setProperty("hudX", Float.toString(hudX));
        snapshot.setProperty("hudY", Float.toString(hudY));
        snapshot.setProperty("hudSize", Integer.toString(hudSize));
        snapshot.setProperty("hudOpacity", Integer.toString(hudOpacity));
        snapshot.setProperty("hudFps", Boolean.toString(hudFps));
        snapshot.setProperty("hudFrame", Boolean.toString(hudFrame));
        snapshot.setProperty("hudLow", Boolean.toString(hudLow));
        snapshot.setProperty("hudHeap", Boolean.toString(hudHeap));
        snapshot.setProperty("hudCpu", Boolean.toString(hudCpu));
        snapshot.setProperty("hudGc", Boolean.toString(hudGc));
        snapshot.setProperty("hudGraph", Boolean.toString(hudGraph));
        snapshot.setProperty("toastPos", Integer.toString(toastPos));
        snapshot.setProperty("cat", Boolean.toString(cat));
        snapshot.setProperty("replay", Boolean.toString(replay));
        snapshot.setProperty("replayFormat", replayFormat);
        snapshot.setProperty("uiScale", Integer.toString(uiScale));
        snapshot.setProperty("browserHome", browserHome);
        snapshot.setProperty("browserSearch", browserSearch);
        snapshot.setProperty("bookmarksBar", Boolean.toString(bookmarksBar));
        snapshot.setProperty("fakeHardware", Boolean.toString(fakeHardware));
        snapshot.setProperty("fakeCpu", fakeCpu);
        snapshot.setProperty("fakeThreads", Integer.toString(fakeThreads));
        snapshot.setProperty("fakeGpu", fakeGpu);
        snapshot.setProperty("fakeVendor", fakeVendor);
        snapshot.setProperty("fakeMemory", Integer.toString(fakeMemory));
        return () -> {
            try {
                Files.createDirectories(dataDir);
                try (OutputStream out = Files.newOutputStream(settingsFile)) {
                    snapshot.store(out, "k0t1k overlay");
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not save " + settingsFile, e);
            }
        };
    }
}
