package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Exercises {@link SqliteReader} against fixtures committed under {@code src/test/resources/sqlite}. */
class SqliteReaderTest {

    @Test
    void readsShortAndOverflowRows() throws IOException, URISyntaxException {
        List<Map<String, Object>> rows = SqliteReader.readTable(resource("sqlite/simple.sqlite"), null, "blobs");
        assertEquals(3, rows.size());

        Map<String, Object> first = byId(rows, 1);
        assertEquals("short", first.get("name"));
        assertEquals("hello", first.get("data"));

        Map<String, Object> overflow = byId(rows, 2);
        assertEquals("long", overflow.get("name"));
        String data = (String) overflow.get("data");
        assertEquals(10000, data.length());
        for (int i = 0; i < data.length(); i++) {
            assertEquals('x', data.charAt(i), "byte " + i + " of the overflowing column");
        }

        Map<String, Object> third = byId(rows, 3);
        assertEquals("third", third.get("name"));
        assertEquals("world", third.get("data"));
    }

    @Test
    void appliesTheWriteAheadLogOnTopOfTheMainFile() throws IOException, URISyntaxException {
        // Without the -wal file, the main file alone does not even have the table: the fixture was
        // copied out before any checkpoint, so everything (schema included) is still only in the log.
        Path dbOnly = resource("sqlite/wal/cookies.sqlite");
        assertTrue(assertIoError(() -> SqliteReader.readTable(dbOnly, null, "moz_cookies")));

        List<Map<String, Object>> rows = SqliteReader.readTable(resource("sqlite/wal/cookies.sqlite"),
                resource("sqlite/wal/cookies.sqlite-wal"), "moz_cookies");
        assertEquals(3, rows.size());

        Map<String, Object> youtube = byHost(rows, ".youtube.com");
        assertEquals("session_token", youtube.get("name"));
        assertEquals("abc123", youtube.get("value"));
        assertEquals(1L, youtube.get("isSecure"));
        assertEquals(1L, youtube.get("isHttpOnly"));

        Map<String, Object> google = byHost(rows, "accounts.google.com");
        assertEquals("sid", google.get("name"));
        assertEquals(0L, google.get("isHttpOnly"));
    }

    @Test
    void missingTableIsReported() throws URISyntaxException {
        Path db = resource("sqlite/simple.sqlite");
        assertTrue(assertIoError(() -> SqliteReader.readTable(db, null, "nope")));
    }

    private static boolean assertIoError(ThrowingRunnable action) {
        try {
            action.run();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    private static Map<String, Object> byId(List<Map<String, Object>> rows, long id) {
        for (Map<String, Object> row : rows) {
            if (((Number) row.get("id")).longValue() == id) {
                return row;
            }
        }
        throw new AssertionError("no row with id " + id);
    }

    private static Map<String, Object> byHost(List<Map<String, Object>> rows, String host) {
        for (Map<String, Object> row : rows) {
            if (host.equals(row.get("host"))) {
                return row;
            }
        }
        throw new AssertionError("no row with host " + host);
    }

    private static Path resource(String name) throws URISyntaxException {
        java.net.URL url = SqliteReaderTest.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalStateException("test resource " + name + " was not found");
        }
        return Paths.get(url.toURI());
    }
}
