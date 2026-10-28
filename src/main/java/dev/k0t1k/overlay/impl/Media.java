package dev.k0t1k.overlay.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * What the system's media session plays (Spotify, a browser tab, any player) and its play/next/previous
 * controls. On Windows a PowerShell child talks to the system media transport controls
 * ({@code media.ps1}); on Linux {@code playerctl} speaks MPRIS. Elsewhere it stays unavailable.
 * Started the first time the overlay opens, so a game that never opens it starts no process.
 */
final class Media {
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");

    /** Whether anything is known to play or be paused right now. */
    volatile boolean active;
    volatile boolean playing;
    volatile String title = "";
    volatile String artist = "";
    /** Set once the backend gave up (no PowerShell, no playerctl). */
    volatile boolean unavailable = !WINDOWS && !LINUX;

    private final ScheduledExecutorService io;
    private final Executor render;
    private final Consumer<Throwable> onError;
    private boolean started;
    private Process bridge;
    private OutputStream commands;
    /** Called on the render thread when a new track starts playing. */
    Consumer<String> onTrack = track -> { };
    private String lastTrack = "";

    Media(ScheduledExecutorService io, Executor render, Consumer<Throwable> onError) {
        this.io = io;
        this.render = render;
        this.onError = onError;
    }

    void start() {
        if (started || unavailable) {
            return;
        }
        started = true;
        if (WINDOWS) {
            io.execute(this::startBridge);
        } else {
            io.scheduleWithFixedDelay(this::pollPlayerctl, 0, 1500, TimeUnit.MILLISECONDS);
        }
    }

    /** Toggles play/pause, or skips with "next" and "prev". */
    void command(String command) {
        if (WINDOWS) {
            OutputStream out = commands;
            if (out == null) {
                return;
            }
            io.execute(() -> {
                try {
                    out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException e) {
                    onError.accept(e);
                }
            });
        } else if (LINUX) {
            String verb = "play".equals(command) ? "play-pause" : "next".equals(command) ? "next" : "previous";
            io.execute(() -> {
                try {
                    run("playerctl", verb);
                } catch (IOException e) {
                    onError.accept(e);
                }
            });
        }
    }

    void stop() {
        Process process = bridge;
        if (process != null) {
            process.destroy();
        }
    }

    private void startBridge() {
        try {
            String script = resource("media.ps1");
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            bridge = process;
            commands = process.getOutputStream();
            Thread reader = new Thread(() -> readBridge(process), "k0t1k-overlay-media");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) {
            unavailable = true;
            onError.accept(e);
        }
    }

    private void readBridge(Process process) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.startsWith("S\t")) {
                    String[] parts = line.split("\t", -1);
                    update(parts.length > 1 && "Playing".equals(parts[1]), part(parts, 2), part(parts, 3));
                } else if ("N".equals(line)) {
                    update(false, "", "");
                    active = false;
                } else if (!line.trim().isEmpty()) {
                    Overlay.log("media: " + line);
                }
            }
        } catch (IOException e) {
            onError.accept(e);
        }
        active = false;
        unavailable = true;
    }

    private void pollPlayerctl() {
        if (unavailable) {
            return;
        }
        try {
            String out = run("playerctl", "metadata", "--format", "{{status}}\t{{title}}\t{{artist}}").trim();
            if (out.isEmpty() || !out.contains("\t")) {
                active = false;
                return;
            }
            String[] parts = out.split("\t", -1);
            update("Playing".equals(parts[0]), part(parts, 1), part(parts, 2));
        } catch (IOException e) {
            // No playerctl installed: say so once and stop polling.
            unavailable = true;
            Overlay.log("media controls need playerctl: " + e.getMessage());
        }
    }

    private void update(boolean nowPlaying, String newTitle, String newArtist) {
        active = !newTitle.isEmpty();
        playing = nowPlaying;
        title = newTitle;
        artist = newArtist;
        String track = label();
        if (nowPlaying && !track.isEmpty() && !track.equals(lastTrack)) {
            lastTrack = track;
            render.execute(() -> onTrack.accept(track));
        }
    }

    /** "Title — Artist", or just the title. */
    String label() {
        String t = title;
        String a = artist;
        return a.isEmpty() ? t : t + " — " + a;
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index].trim() : "";
    }

    private static String run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = read(process.getInputStream());
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Media.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " is missing from the overlay jar");
            }
            return read(in);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
