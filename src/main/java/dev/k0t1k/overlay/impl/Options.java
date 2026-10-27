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
 * </ul>
 */
final class Options {
    final Path gameDir;
    final Path dataDir;
    final Strings strings;
    /** The player's name, for highlighting mentions; empty when unknown. */
    final String username;

    // Saved settings.
    boolean hud;
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    int hudCorner;

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
        strings = new Strings(option(args, "lang", "en"));
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
