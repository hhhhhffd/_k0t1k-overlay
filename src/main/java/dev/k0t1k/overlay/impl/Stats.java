package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lifetime counters across every game started with the overlay (play time, deaths, crashes...) and the
 * launcher's own achievements on top of them. The file lives in the shared data folder, so two games
 * may run at once: each save re-reads it and adds only this game's changes since the last save.
 *
 * <p>Counters change on the render thread; {@link #saveTask} snapshots them for the io thread.
 */
final class Stats {
    static final String[] COUNTERS = {"playMillis", "sessions", "deaths", "advancements", "screenshots", "crashes", "timers"};

    /** An achievement: unlocked when {@code counter} reaches {@code goal}; a null counter is checked by hand. */
    static final class Achievement {
        final String id;
        final String counter;
        final long goal;

        Achievement(String id, String counter, long goal) {
            this.id = id;
            this.counter = counter;
            this.goal = goal;
        }
    }

    static final long HOUR = 3_600_000L;
    static final Achievement[] ACHIEVEMENTS = {
        new Achievement("hour1", "playMillis", HOUR),
        new Achievement("hour10", "playMillis", 10 * HOUR),
        new Achievement("hour100", "playMillis", 100 * HOUR),
        new Achievement("sessions50", "sessions", 50),
        new Achievement("death1", "deaths", 1),
        new Achievement("death9", "deaths", 9),
        new Achievement("adv10", "advancements", 10),
        new Achievement("shot10", "screenshots", 10),
        new Achievement("crash1", "crashes", 1),
        new Achievement("crash10", "crashes", 10),
        new Achievement("timer1", "timers", 1),
        new Achievement("nightOwl", null, 0),
    };

    private final Path file;
    private final Path lock;
    private final Path crashReports;
    private final long started = System.currentTimeMillis();
    private final Properties totals = new Properties();
    /** This game's changes not yet written. */
    private final Properties pending = new Properties();
    final Set<String> unlocked = new LinkedHashSet<>();
    private long lastTick = started;
    /** Called on the render thread with each newly unlocked achievement id. */
    Consumer<String> onUnlock = id -> { };

    Stats(Path dataDir, Path gameDir) {
        file = dataDir.resolve("stats.properties");
        // One lock per game folder: a lock left behind means that game did not exit cleanly.
        lock = dataDir.resolve("session-" + Integer.toHexString(gameDir.toString().hashCode()) + ".lock");
        crashReports = gameDir.resolve("crash-reports");
    }

    /** Reads the file and records this session; on the io thread before anything else uses the stats. */
    void load() throws IOException {
        readInto(totals);
        for (String id : totals.getProperty("unlocked", "").split(",")) {
            if (!id.isEmpty()) {
                unlocked.add(id);
            }
        }
        add("sessions", 1);
        if (Files.exists(lock)) {
            add("crashes", 1);
        }
        Files.createDirectories(file.getParent());
        Files.write(lock, Long.toString(started).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    long get(String counter) {
        return parse(totals.getProperty(counter)) + parse(pending.getProperty(counter));
    }

    void add(String counter, long amount) {
        pending.setProperty(counter, Long.toString(parse(pending.getProperty(counter)) + amount));
        check();
    }

    /** Once per frame: counts play time and checks the time-based achievements. */
    void tick(long now) {
        if (now - lastTick >= 1000) {
            add("playMillis", now - lastTick);
            lastTick = now;
            int hour = LocalTime.now().getHour();
            if (hour >= 3 && hour < 5) {
                unlock("nightOwl");
            }
        }
    }

    private void check() {
        for (Achievement a : ACHIEVEMENTS) {
            if (a.counter != null && !unlocked.contains(a.id) && get(a.counter) >= a.goal) {
                unlock(a.id);
            }
        }
    }

    private void unlock(String id) {
        if (unlocked.add(id)) {
            onUnlock.accept(id);
        }
    }

    /** A snapshot of this game's changes for the io thread; merges them into the file there. */
    Runnable saveTask() {
        Properties delta = new Properties();
        delta.putAll(pending);
        for (String key : pending.stringPropertyNames()) {
            totals.setProperty(key, Long.toString(parse(totals.getProperty(key)) + parse(pending.getProperty(key))));
        }
        pending.clear();
        String ids = String.join(",", unlocked);
        return () -> {
            try {
                merge(delta, ids);
            } catch (IOException e) {
                throw new IllegalStateException("could not save " + file, e);
            }
        };
    }

    /** On JVM exit: a crash report written during this session counts as a crash; then the lock goes. */
    void saveOnExit() {
        try {
            if (crashedThisSession()) {
                add("crashes", 1);
            }
            saveTask().run();
            Files.deleteIfExists(lock);
        } catch (IOException | RuntimeException e) {
            System.err.println("[k0t1k-overlay] could not save stats: " + e);
        }
    }

    private boolean crashedThisSession() throws IOException {
        if (!Files.isDirectory(crashReports)) {
            return false;
        }
        try (DirectoryStream<Path> reports = Files.newDirectoryStream(crashReports, "crash-*.txt")) {
            for (Path report : reports) {
                if (Files.getLastModifiedTime(report).toMillis() >= started) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void merge(Properties delta, String ids) throws IOException {
        Properties current = new Properties();
        readInto(current);
        for (String key : delta.stringPropertyNames()) {
            current.setProperty(key, Long.toString(parse(current.getProperty(key)) + parse(delta.getProperty(key))));
        }
        Set<String> all = new LinkedHashSet<>();
        for (String id : (current.getProperty("unlocked", "") + "," + ids).split(",")) {
            if (!id.isEmpty()) {
                all.add(id);
            }
        }
        current.setProperty("unlocked", String.join(",", all));
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling("stats.properties.tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            current.store(out, "k0t1k overlay stats");
        }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private void readInto(Properties target) throws IOException {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                target.load(in);
            }
        }
    }

    private static long parse(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
