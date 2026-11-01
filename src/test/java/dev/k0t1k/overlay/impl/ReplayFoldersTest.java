package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The replay buffer folders of games sharing one data folder. */
class ReplayFoldersTest {
    @Test
    void cleanupKeepsLockedFoldersAndRemovesAbandonedOnes(@TempDir Path root) throws IOException {
        Path own = Files.createDirectory(root.resolve("1-1"));
        Path alive = Files.createDirectory(root.resolve("2-2"));
        Path dead = Files.createDirectory(root.resolve("3-3"));
        Path starting = Files.createDirectory(root.resolve("4-4"));
        Files.createFile(own.resolve("seg00000.mkv"));
        Files.createFile(alive.resolve("seg00000.mkv"));
        Files.createFile(dead.resolve("seg00000.mkv"));
        Path loose = Files.createFile(root.resolve("seg00007.mkv"));

        try (FileChannel aliveLock = Replay.lock(alive)) {
            assertTrue(aliveLock.isOpen());
            // A game that died: its lock file is left behind with nobody holding it.
            Replay.lock(dead).close();

            Replay.removeStale(root, own);

            assertTrue(Files.exists(own.resolve("seg00000.mkv")));
            assertTrue(Files.exists(alive.resolve("seg00000.mkv")));
            assertFalse(Files.exists(dead));
            assertTrue(Files.exists(starting)); // no lock file yet and brand new: a game still starting
            assertFalse(Files.exists(loose));
        }
    }
}
