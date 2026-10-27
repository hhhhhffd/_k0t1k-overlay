package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Notes, one UTF-8 text file per note in {@code notes/}, named by creation time. The render thread
 * owns {@link #notes}; disk work runs on {@code io}.
 */
final class Notes {
    private static final long SAVE_DELAY_MS = 800;

    static final class Note {
        final String id;
        final TextEdit edit;
        long modified;
        boolean dirty;
        long dirtySince;

        Note(String id, String text, long modified) {
            this.id = id;
            this.edit = new TextEdit(text);
            this.modified = modified;
        }

        String title() {
            String text = edit.value().trim();
            int newline = text.indexOf('\n');
            return newline < 0 ? text : text.substring(0, newline).trim();
        }
    }

    private final Path dir;
    private final Executor io;
    private final Executor render;
    private final Consumer<Throwable> onError;
    /** Newest first. */
    final List<Note> notes = new ArrayList<>();
    boolean loaded;

    Notes(Path dataDir, Executor io, Executor render, Consumer<Throwable> onError) {
        this.dir = dataDir.resolve("notes");
        this.io = io;
        this.render = render;
        this.onError = onError;
    }

    void load() {
        io.execute(() -> {
            List<Note> found = new ArrayList<>();
            try {
                if (Files.isDirectory(dir)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.txt")) {
                        for (Path file : files) {
                            String name = file.getFileName().toString();
                            found.add(new Note(name.substring(0, name.length() - 4),
                                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8),
                                    Files.getLastModifiedTime(file).toMillis()));
                        }
                    }
                }
            } catch (IOException e) {
                onError.accept(e);
            }
            found.sort(Comparator.comparingLong((Note note) -> note.modified).reversed());
            render.execute(() -> {
                notes.addAll(found);
                loaded = true;
            });
        });
    }

    /** A new empty note; it reaches the disk with its first edit. */
    Note create() {
        Note note = new Note(Long.toString(System.currentTimeMillis()), "", System.currentTimeMillis());
        notes.add(0, note);
        return note;
    }

    void changed(Note note) {
        note.modified = System.currentTimeMillis();
        if (!note.dirty) {
            note.dirty = true;
            note.dirtySince = note.modified;
        }
    }

    /** Saves notes edited a moment ago; with {@code now} set, every unsaved note. */
    void flush(boolean now) {
        long time = System.currentTimeMillis();
        for (Note note : notes) {
            if (note.dirty && (now || time - note.dirtySince >= SAVE_DELAY_MS)) {
                note.dirty = false;
                save(note.id, note.edit.value());
            }
        }
    }

    /** Writes every unsaved note right here, for the exit hook when the io thread may be gone. */
    void saveNow() {
        for (Note note : new ArrayList<>(notes)) {
            if (note.dirty) {
                note.dirty = false;
                write(note.id, note.edit.value());
            }
        }
    }

    void delete(Note note) {
        notes.remove(note);
        String id = note.id;
        io.execute(() -> {
            try {
                Files.deleteIfExists(dir.resolve(id + ".txt"));
            } catch (IOException e) {
                onError.accept(e);
            }
        });
    }

    private void save(String id, String text) {
        io.execute(() -> write(id, text));
    }

    private synchronized void write(String id, String text) {
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(id + ".txt");
            Path temp = dir.resolve(id + ".txt.tmp");
            Files.write(temp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            onError.accept(e);
        }
    }
}
