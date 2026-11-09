package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chat history keeps a bounded number of messages in memory and pages the rest from its store. */
class ChatHistoryTest {
    @Test
    void searchKeepsStoreOffsetsAcrossBufferBoundariesAndDuplicateMessages(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("data").resolve("chat-history.tsv");
        Files.createDirectories(store.getParent());
        String largeRow = storedRow(1, "server", repeated('x', 65_530) + " λ");
        String firstMatch = storedRow(2, "server", "needle 😊");
        String secondMatch = storedRow(3, "server", "needle 😊");
        byte[] largeBytes = largeRow.getBytes(StandardCharsets.UTF_8);
        byte[] firstBytes = firstMatch.getBytes(StandardCharsets.UTF_8);
        Files.write(store, largeRow.getBytes(StandardCharsets.UTF_8));

        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
        try {
            CompletableFuture<List<ChatHistory.Line>> completion = new CompletableFuture<>();
            ChatHistory history = new ChatHistory(dir.resolve("game"), dir.resolve("data"), io, io,
                    Runnable::run, completion::completeExceptionally);
            history.start();
            settle(io);
            settle(io);

            String after = storedRow(4, "server", "following context");
            Files.write(store, (firstMatch + secondMatch + after).getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            history.search("needle", completion::complete);
            List<ChatHistory.Line> found = completion.get(10, TimeUnit.SECONDS);

            assertEquals(2, found.size());
            assertEquals(largeBytes.length, found.get(0).start);
            assertEquals(largeBytes.length + firstBytes.length, found.get(1).start);
            history.loadMatch(found.get(0));
            settle(io);
            assertEquals(4, history.lines.size());
            assertEquals("following context", history.lines.get(3).text);
            history.loadMatch(found.get(1));
            settle(io);

            assertEquals(4, history.lines.size());
            assertEquals(found.get(0).start, history.lines.get(1).start);
            assertEquals(found.get(1).start, history.lines.get(2).start);
            assertTrue(found.get(0).start != found.get(1).start);
        } finally {
            io.shutdownNow();
        }
    }

    @Test
    void longSessionsDropTheOldestAndLoadThemBackFromTheStore(@TempDir Path dir) throws Exception {
        Path game = dir.resolve("game");
        int total = 7500;
        writeLog(game, 0, total);
        Files.createDirectories(dir.resolve("data"));
        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
        try {
            ChatHistory history = new ChatHistory(game, dir.resolve("data"), io, io, Runnable::run, e -> {
                throw new AssertionError(e);
            });
            // Runs everything, the render thread's work included, on the io thread: once for the start,
            // once more for the first poll that the start schedules.
            history.start();
            settle(io);
            settle(io);

            assertEquals(5000, history.lines.size());
            assertTrue(history.hasOlder);
            assertEquals("message " + (total - 5000), history.lines.get(0).text);
            assertEquals("message " + (total - 1), history.lines.get(4999).text);

            io.submit(history::loadOlder).get(10, TimeUnit.SECONDS);
            settle(io);

            // The page before the first kept message joins on without a gap or a repeat.
            assertEquals(7000, history.lines.size());
            for (int i = 0; i < history.lines.size(); i++) {
                assertEquals("message " + (total - 7000 + i), history.lines.get(i).text);
            }
            assertTrue(history.hasOlder);
        } finally {
            io.shutdownNow();
        }
    }

    private static void settle(ScheduledExecutorService io) throws Exception {
        io.submit(() -> { }).get(10, TimeUnit.SECONDS);
    }

    private static void writeLog(Path game, int from, int to) throws IOException {
        StringBuilder log = new StringBuilder();
        for (int i = from; i < to; i++) {
            log.append("[12:00:00] [Render thread/INFO]: [CHAT] message ").append(i).append('\n');
        }
        Path file = game.resolve("logs").resolve("latest.log");
        Files.createDirectories(file.getParent());
        Files.write(file, log.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String storedRow(long time, String server, String text) {
        return time + "\t" + ChatHistory.escape(server) + "\t" + ChatHistory.escape(text) + "\n";
    }

    private static String repeated(char c, int count) {
        StringBuilder text = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            text.append(c);
        }
        return text.toString();
    }
}
