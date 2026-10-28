package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Countdown timers and alarms ("check the farm in 20 minutes"). They are stored with their absolute
 * due time, so they survive a restart of the game; one that came due while the game was closed fires
 * on the next start. Lives on the render thread; files are written on the io thread.
 */
final class Timers {
    static final class Timer {
        final String label;
        final long due;
        final long length;

        Timer(String label, long due, long length) {
            this.label = label;
            this.due = due;
            this.length = length;
        }
    }

    final List<Timer> timers = new ArrayList<>();
    private final Path file;
    private final Executor io;
    private final Consumer<Throwable> onError;
    /** Called on the render thread when a timer fires. */
    Consumer<Timer> onFire = timer -> { };

    Timers(Path dataDir, Executor io, Consumer<Throwable> onError) {
        this.file = dataDir.resolve("timers.tsv");
        this.io = io;
        this.onError = onError;
    }

    /** Reads the saved timers; a small file, read once at start. */
    void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            for (String row : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = row.split("\t", 3);
                if (parts.length == 3) {
                    timers.add(new Timer(parts[2], Long.parseLong(parts[0]), Long.parseLong(parts[1])));
                }
            }
        } catch (IOException | NumberFormatException e) {
            onError.accept(e);
        }
    }

    /**
     * Parses what the player typed: minutes ("20", "1.5") for a countdown, or a clock time ("14:30") for
     * an alarm, which is tomorrow when that time has passed today. Returns the due time, or -1.
     */
    static long parseDue(String text, long now) {
        String value = text.trim().replace(',', '.');
        if (value.isEmpty()) {
            return -1;
        }
        try {
            if (value.contains(":")) {
                String[] parts = value.split(":");
                LocalTime at = LocalTime.of(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                ZoneId zone = ZoneId.systemDefault();
                LocalDateTime today = LocalDateTime.now(zone).with(at).withSecond(0).withNano(0);
                long due = today.atZone(zone).toInstant().toEpochMilli();
                return due <= now ? today.plusDays(1).atZone(zone).toInstant().toEpochMilli() : due;
            }
            double minutes = Double.parseDouble(value);
            if (minutes <= 0 || minutes > 7 * 24 * 60) {
                return -1;
            }
            return now + Math.round(minutes * 60_000);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    void add(String label, long due, long now) {
        timers.add(new Timer(label, due, due - now));
        timers.sort((a, b) -> Long.compare(a.due, b.due));
        save();
    }

    void remove(Timer timer) {
        if (timers.remove(timer)) {
            save();
        }
    }

    /** Once per frame: fires the timers that came due. */
    void tick(long now) {
        boolean fired = false;
        while (!timers.isEmpty() && timers.get(0).due <= now) {
            onFire.accept(timers.remove(0));
            fired = true;
        }
        if (fired) {
            save();
        }
    }

    private void save() {
        StringBuilder out = new StringBuilder();
        for (Timer timer : timers) {
            out.append(timer.due).append('\t').append(timer.length).append('\t')
                    .append(timer.label.replace('\t', ' ').replace('\n', ' ')).append('\n');
        }
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            } catch (IOException e) {
                throw new IllegalStateException("could not save " + file, e);
            }
        });
    }

    /** "1:05:09" or "4:59". */
    static String remaining(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        long h = s / 3600;
        long m = s / 60 % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s % 60) : String.format("%d:%02d", m, s % 60);
    }
}
