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

    final Path gameDir;
    final Path dataDir;
    final Strings strings;
    /** The player's name, for highlighting mentions; empty when unknown. */
    final String username;

    // Saved settings.
    boolean hud;
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    int hudCorner;
    /** Whether the cat pops up to react to deaths, advancements, AFK and timers. */
    boolean cat = true;
    /** Whether the last seconds of the game are kept for Shift+F2 (OpenGL 3.2 and Vulkan). */
    boolean replay = true;
    /** "video" (MP4 with ffmpeg, otherwise AVI) or "gif". */
    String replayFormat = "video";
    /** The interface scale in percent, {@link #MIN_SCALE} to {@link #MAX_SCALE}, or 0 for automatic. */
    int uiScale;
    /** The launcher's interface zoom in percent, or 0 when it is on automatic. */
    final int launcherScale;
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
        try {
            hudCorner = Math.max(0, Math.min(3, Integer.parseInt(saved.getProperty("hudCorner", "0"))));
        } catch (NumberFormatException e) {
            hudCorner = 0;
        }
    }

    /** Runs on the io thread; takes a snapshot so the render thread can keep changing the fields. */
    Runnable saveTask() {
        Properties snapshot = new Properties();
        snapshot.setProperty("hud", Boolean.toString(hud));
        snapshot.setProperty("hudCorner", Integer.toString(hudCorner));
        snapshot.setProperty("cat", Boolean.toString(cat));
        snapshot.setProperty("replay", Boolean.toString(replay));
        snapshot.setProperty("replayFormat", replayFormat);
        snapshot.setProperty("uiScale", Integer.toString(uiScale));
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
