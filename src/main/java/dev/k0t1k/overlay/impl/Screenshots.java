package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Notices the screenshots the game saves (F2) by watching {@code <gameDir>/screenshots}, and keeps a
 * caption for each in {@code <dataDir>/screenshots.tsv}: file name, time, instance, dimension and
 * position, so the launcher's gallery can show where a shot was taken.
 */
final class Screenshots {
    final Path folder;
    private final Path captions;
    private final ScheduledExecutorService io;
    private final Executor render;
    private final Consumer<Throwable> onError;
    private final Set<String> known = new HashSet<>();
    /** The newest file names first, for the tools tab; render thread only. */
    final List<String> recent = new ArrayList<>();
    /** Called on the render thread with each new file name. */
    Consumer<String> onNew = name -> { };

    Screenshots(Path gameDir, Path dataDir, ScheduledExecutorService io, Executor render, Consumer<Throwable> onError) {
        this.folder = gameDir.resolve("screenshots");
        this.captions = dataDir.resolve("screenshots.tsv");
        this.io = io;
        this.render = render;
        this.onError = onError;
    }

    void start() {
        io.execute(() -> {
            try {
                known.addAll(list());
            } catch (IOException e) {
                onError.accept(e);
            }
            io.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
        });
    }

    private void poll() {
        try {
            for (String name : list()) {
                if (known.add(name)) {
                    render.execute(() -> {
                        recent.add(0, name);
                        if (recent.size() > 8) {
                            recent.remove(recent.size() - 1);
                        }
                        onNew.accept(name);
                    });
                }
            }
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
        }
    }

    private List<String> list() throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.png")) {
            for (Path file : files) {
                names.add(file.getFileName().toString());
            }
        }
        names.sort(null);
        return names;
    }

    /** Appends one caption line; called on the render thread, written on the io thread. */
    void caption(String name, String instance, String dimension, String position) {
        String row = String.join("\t", name, Long.toString(System.currentTimeMillis()), clean(instance),
                clean(dimension), clean(position)) + "\n";
        io.execute(() -> {
            try {
                Files.createDirectories(captions.getParent());
                Files.write(captions, row.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                onError.accept(e);
            }
        });
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replace('\n', ' ');
    }
}
