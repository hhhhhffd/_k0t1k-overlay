package dev.k0t1k.overlay.impl;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Crash insurance. While the game runs it records exceptions that kill any thread; when the game
 * exits after writing a crash report, it collects what only the live JVM still knows (a dump of every
 * thread, those exceptions, the overlay's view of the session) and starts the crash window
 * ({@code dev.k0t1k.overlay.CrashDialog}) in a JVM of its own, which outlives the game.
 */
final class CrashGuard {
    /** Where "Send to support" leads after the upload. */
    static final String SUPPORT = "https://discord.gg/zxnMAfUvJ";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Path folder;
    private final Deque<String> uncaught = new ArrayDeque<>();

    CrashGuard(Path dataDir) {
        this.folder = dataDir.resolve("crashes");
    }

    /** Records uncaught exceptions, then hands them on to whatever handler was there before. */
    void install() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            synchronized (uncaught) {
                uncaught.addLast("Thread \"" + thread.getName() + "\": " + trace);
                if (uncaught.size() > 5) {
                    uncaught.removeFirst();
                }
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                System.err.print("Exception in thread \"" + thread.getName() + "\" ");
                error.printStackTrace();
            }
        });
    }

    /**
     * From the shutdown hook, when the game crashed: writes the bundle and opens the crash window.
     * {@code summary} is the overlay's own notes on the session.
     */
    void onCrash(Path report, String lang, String summary) throws IOException {
        Files.createDirectories(folder);
        Path bundle = folder.resolve("crash_" + LocalDateTime.now().format(NAME) + ".txt");
        StringBuilder text = new StringBuilder();
        text.append("---- _k0t1k overlay: notes from the live game ----\n").append(summary).append('\n');
        synchronized (uncaught) {
            if (!uncaught.isEmpty()) {
                text.append("\n-- Uncaught exceptions --\n");
                for (String entry : uncaught) {
                    text.append(entry).append('\n');
                }
            }
        }
        text.append("\n-- Threads at exit --\n");
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)) {
            text.append(info.toString());
        }
        Files.write(bundle, text.toString().getBytes(StandardCharsets.UTF_8));
        launch(lang, report, bundle);
    }

    private static void launch(String lang, Path report, Path bundle) throws IOException {
        String jar;
        try {
            jar = new File(CrashGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
        } catch (URISyntaxException | RuntimeException e) {
            throw new IOException("cannot locate the overlay jar", e);
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        File java = new File(System.getProperty("java.home"), windows ? "bin/javaw.exe" : "bin/java");
        ProcessBuilder builder = new ProcessBuilder(java.getAbsolutePath(), "-cp", jar,
                "dev.k0t1k.overlay.CrashDialog", lang, report.toString(), bundle.toString(), SUPPORT);
        File discard = new File(windows ? "NUL" : "/dev/null");
        builder.redirectErrorStream(true).redirectOutput(discard).redirectInput(ProcessBuilder.Redirect.from(discard));
        builder.start();
        Overlay.log("crash window started for " + report);
    }
}
