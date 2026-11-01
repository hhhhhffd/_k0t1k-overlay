package dev.k0t1k.overlay.impl;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Audio for recordings. A helper process captures one source as raw 48 kHz stereo 16-bit samples and
 * the overlay pumps its output into a loopback socket that ffmpeg reads as {@code tcp://127.0.0.1:PORT}.
 *
 * <p>On Windows the helper is {@code cap.ps1}, which compiles {@code Cap.cs} with PowerShell and uses
 * WASAPI: the game's own sound (process loopback, Windows 10 2004 and later), everything except the game,
 * or the default microphone. On Linux it is {@code parec}: the game's sink input, the default monitor
 * (which includes the game) or the default source. Linux support is best effort.
 */
final class AudioCapture {
    enum Kind { GAME, REST, MIC }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    /** 48 kHz, 2 channels, 16 bits. */
    private static final long BYTES_PER_SECOND = 48000L * 4;
    /** If the video does not start, keep at most this much audio waiting (about 20 seconds). */
    private static final int MAX_HELD = 4_000_000;
    /** Compiling the helper takes a few seconds the first time. */
    private static final int FIRST_BYTES_SECONDS = 30;

    /** Sources that failed once in this game; they are not tried again. */
    private static final Set<Kind> FAILED = Collections.synchronizedSet(EnumSet.noneOf(Kind.class));

    private AudioCapture() {
    }

    /** Whether the platform can capture at all. */
    static boolean supported() {
        return WINDOWS || LINUX;
    }

    static boolean failed(Kind kind) {
        return FAILED.contains(kind);
    }

    /** One running capture, readable by ffmpeg at {@link #input()}. */
    static final class Feed {
        final Kind kind;
        private final ServerSocket server;
        private final Process helper;
        private final Thread pump;
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile String lastError = "";
        private volatile boolean closed;
        private volatile Socket client;
        /** When the first video frame went to ffmpeg ({@link System#nanoTime}); audio captured before is dropped. */
        private volatile long alignNanos;
        private volatile boolean aligned;

        private Feed(Kind kind, Process helper) throws IOException {
            this.kind = kind;
            this.helper = helper;
            try {
                this.server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            } catch (IOException e) {
                helper.destroy();
                throw e;
            }
            Thread errors = new Thread(this::readErrors, "k0t1k-overlay-audio-errors");
            errors.setDaemon(true);
            errors.start();
            pump = new Thread(this::run, "k0t1k-overlay-audio-" + kind);
            pump.setDaemon(true);
            pump.start();
        }

        /** The ffmpeg input address. */
        String input() {
            return "tcp://127.0.0.1:" + server.getLocalPort();
        }

        private void readErrors() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(helper.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.trim().isEmpty()) {
                        lastError = line.trim();
                    }
                }
            } catch (IOException e) {
                // The helper is gone; its exit shows in the pump.
            }
        }

        /**
         * Tells the feed when the video starts. ffmpeg stamps the audio from zero at its first sample and
         * the video from zero at its first frame, so the audio captured before this instant (helpers are
         * ready before ffmpeg is) is cut from the stream's front, counted in bytes from the moment the
         * helper's first samples arrived.
         */
        void alignAt(long nanos) {
            alignNanos = nanos;
            aligned = true;
        }

        private void run() {
            byte[] buffer = new byte[1 << 15];
            try (InputStream in = helper.getInputStream()) {
                int n = in.read(buffer);
                if (n <= 0) {
                    return;
                }
                // These samples were captured just before they arrived.
                long streamStart = System.nanoTime() - n * 1_000_000_000L / BYTES_PER_SECOND;
                started.countDown();
                // ffmpeg connects when it starts; the samples wait here for that and for the video start.
                Thread acceptor = new Thread(this::accept, "k0t1k-overlay-audio-accept");
                acceptor.setDaemon(true);
                acceptor.start();
                ByteArrayOutputStream held = new ByteArrayOutputStream();
                long discarded = 0;
                OutputStream out = null;
                long skip = 0;
                for (;;) {
                    if (out == null && client != null && aligned) {
                        out = client.getOutputStream();
                        long wanted = (alignNanos - streamStart) * BYTES_PER_SECOND / 1_000_000_000L;
                        skip = Math.max(0, wanted - discarded) / 4 * 4; // whole sample frames
                        byte[] early = held.toByteArray();
                        int cut = (int) Math.min(skip, early.length);
                        skip -= cut;
                        out.write(early, cut, early.length - cut);
                    }
                    if (out == null) {
                        held.write(buffer, 0, n);
                        if (held.size() > MAX_HELD) {
                            discarded += held.size();
                            held.reset();
                        }
                    } else {
                        int cut = (int) Math.min(skip, n);
                        skip -= cut;
                        out.write(buffer, cut, n - cut);
                    }
                    if ((n = in.read(buffer)) <= 0) {
                        break;
                    }
                }
                if (out != null) {
                    out.close();
                }
            } catch (IOException e) {
                if (!closed) {
                    lastError = e.toString();
                }
            } finally {
                started.countDown();
            }
        }

        private void accept() {
            try {
                client = server.accept();
            } catch (IOException e) {
                if (!closed) {
                    lastError = e.toString();
                }
            }
        }

        /** Waits for the helper's first samples; false when it ended without any. */
        private boolean awaitStart() throws InterruptedException {
            return started.await(FIRST_BYTES_SECONDS, TimeUnit.SECONDS) && pump.isAlive();
        }

        /** Stops the helper and ends ffmpeg's input. */
        void close() {
            closed = true;
            try {
                helper.getOutputStream().close(); // the Windows helper stops on end of its input
            } catch (IOException e) {
                // Already gone.
            }
            helper.destroy();
            try {
                server.close();
                Socket socket = client;
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                // Closing; nothing left to lose.
            }
        }
    }

    /**
     * Starts the helper for {@code kind} and waits until it delivers samples.
     *
     * @throws IOException with a reason when the source cannot be captured; on Windows the source is then
     *     left out for the rest of the game
     */
    static Feed start(Kind kind, Path dataDir) throws IOException {
        if (failed(kind)) {
            throw new IOException("unavailable");
        }
        Feed feed = null;
        try {
            feed = new Feed(kind, WINDOWS ? windowsHelper(kind, dataDir) : linuxHelper(kind));
            if (!feed.awaitStart()) {
                String reason = feed.lastError.isEmpty() ? "no audio arrived" : feed.lastError;
                throw new IOException(reason);
            }
            return feed;
        } catch (IOException e) {
            if (feed != null) {
                feed.close();
            }
            if (WINDOWS) {
                FAILED.add(kind);
            }
            throw e;
        } catch (InterruptedException e) {
            feed.close();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static String pid() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }

    // ---- Windows ----

    /** The folder with the helper files, ready once per game. */
    private static Path preparedFolder;

    /**
     * Puts {@code cap.ps1} and {@code Cap.cs} in place, once, before any helper starts: PowerShell reads
     * and compiles them, so a file another helper (or another game running this overlay) is using must not
     * be rewritten. A file is replaced, through a temporary file and an atomic move, only when it differs
     * from the jar's (an updated overlay); if it is in use and the move fails, the existing one stays.
     */
    private static synchronized Path prepareHelper(Path dataDir) throws IOException {
        if (preparedFolder != null) {
            return preparedFolder;
        }
        Path folder = dataDir.resolve("audio");
        Files.createDirectories(folder);
        for (String name : new String[] {"cap.ps1", "Cap.cs"}) {
            byte[] content;
            try (InputStream in = AudioCapture.class.getResourceAsStream(name)) {
                if (in == null) {
                    throw new IOException(name + " is missing from the overlay jar");
                }
                content = readAll(in);
            }
            Path target = folder.resolve(name);
            if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), content)) {
                continue;
            }
            Path temp = Files.createTempFile(folder, name, ".tmp");
            try {
                Files.write(temp, content);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                Files.deleteIfExists(temp);
                if (!Files.isRegularFile(target)) {
                    throw e;
                }
            }
        }
        preparedFolder = folder;
        return folder;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 13];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static Process windowsHelper(Kind kind, Path dataDir) throws IOException {
        Path folder = prepareHelper(dataDir);
        String mode = kind == Kind.GAME ? "game" : kind == Kind.REST ? "rest" : "mic";
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-MTA", "-ExecutionPolicy", "Bypass",
                "-File", folder.resolve("cap.ps1").toString(), mode, pid()).start();
    }

    // ---- Linux ----

    private static Process linuxHelper(Kind kind) throws IOException {
        List<String> command = new ArrayList<>(Arrays.asList("parec"));
        if (kind == Kind.GAME) {
            String[] target = gameSinkInput();
            command.add("--monitor-stream=" + target[0]);
            command.add("-d");
            command.add(target[1] + ".monitor");
        } else {
            command.add("-d");
            command.add(kind == Kind.REST ? "@DEFAULT_MONITOR@" : "@DEFAULT_SOURCE@");
        }
        command.addAll(Arrays.asList("--format=s16le", "--rate=48000", "--channels=2"));
        return new ProcessBuilder(command).start();
    }

    /** {sink input index, sink name} of the stream the game plays on. */
    private static String[] gameSinkInput() throws IOException {
        String inputs = pactl("list", "sink-inputs");
        String pid = "application.process.id = \"" + pid() + "\"";
        String sinkId = null;
        String index = null;
        for (String block : inputs.split("(?m)^Sink Input #")) {
            if (!block.contains(pid)) {
                continue;
            }
            index = block.substring(0, block.indexOf('\n')).trim();
            for (String line : block.split("\n")) {
                if (line.trim().startsWith("Sink:")) {
                    sinkId = line.substring(line.indexOf(':') + 1).trim();
                }
            }
            break;
        }
        if (index == null || sinkId == null) {
            throw new IOException("the game is not playing sound yet");
        }
        for (String line : pactl("list", "short", "sinks").split("\n")) {
            String[] columns = line.split("\t");
            if (columns.length > 1 && columns[0].equals(sinkId)) {
                return new String[] {index, columns[1]};
            }
        }
        throw new IOException("the game's output device was not found");
    }

    private static String pactl(String... arguments) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("pactl");
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C"); // the parser reads English field names
        Process process = builder.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }
}
