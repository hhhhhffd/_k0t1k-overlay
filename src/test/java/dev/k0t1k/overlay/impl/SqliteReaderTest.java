package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
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
    void ignoresWalFramesAfterTheFirstBadChecksum() throws IOException, URISyntaxException {
        Path db = resource("sqlite/wal/cookies.sqlite");
        byte[] wal = Files.readAllBytes(resource("sqlite/wal/cookies.sqlite-wal"));
        int frameSize = 24 + (int) readU32(wal, 8);
        Path corruptedWal = Files.createTempFile("cookies-corrupt", ".wal");
        Path precedingWal = Files.createTempFile("cookies-prefix", ".wal");
        try {
            wal[wal.length - 1] ^= 1;
            Files.write(corruptedWal, wal);
            Files.write(precedingWal, java.util.Arrays.copyOf(wal, wal.length - frameSize));
            assertEquals(SqliteReader.readTable(db, precedingWal, "moz_cookies"),
                    SqliteReader.readTable(db, corruptedWal, "moz_cookies"));
        } finally {
            Files.deleteIfExists(corruptedWal);
            Files.deleteIfExists(precedingWal);
        }
    }

    @Test
    void rejectsWalWithCorruptHeaderChecksum() throws IOException, URISyntaxException {
        Path db = resource("sqlite/wal/cookies.sqlite");
        byte[] wal = Files.readAllBytes(resource("sqlite/wal/cookies.sqlite-wal"));
        wal[24] ^= 1;
        Path corruptedWal = Files.createTempFile("cookies-header", ".wal");
        try {
            Files.write(corruptedWal, wal);
            assertTrue(assertIoError(() -> SqliteReader.readTable(db, corruptedWal, "moz_cookies")));
        } finally {
            Files.deleteIfExists(corruptedWal);
        }
    }

    @Test
    void supportsBigEndianWalChecksums() throws IOException, URISyntaxException {
        byte[] wal = Files.readAllBytes(resource("sqlite/wal/cookies.sqlite-wal"));
        writeU32(wal, 0, 0x377f0683L);
        rechecksumWal(wal, true);
        Path bigEndianWal = Files.createTempFile("cookies-big-endian", ".wal");
        try {
            Files.write(bigEndianWal, wal);
            assertEquals(3, SqliteReader.readTable(resource("sqlite/wal/cookies.sqlite"), bigEndianWal,
                    "moz_cookies").size());
        } finally {
            Files.deleteIfExists(bigEndianWal);
        }
    }

    @Test
    void stopsAtCorruptFrameBeforeLaterCommit() throws IOException, URISyntaxException {
        Path db = resource("sqlite/wal/cookies.sqlite");
        byte[] wal = Files.readAllBytes(resource("sqlite/wal/cookies.sqlite-wal"));
        int frameSize = 24 + (int) readU32(wal, 8);
        wal[32 + frameSize + 24] ^= 1;
        Path corruptedWal = Files.createTempFile("cookies-middle", ".wal");
        try {
            Files.write(corruptedWal, wal);
            assertTrue(assertIoError(() -> SqliteReader.readTable(db, corruptedWal, "moz_cookies")));
        } finally {
            Files.deleteIfExists(corruptedWal);
        }
    }

    @Test
    void rejectsDatabasesWithReservedPageBytes() throws IOException, URISyntaxException {
        byte[] database = Files.readAllBytes(resource("sqlite/simple.sqlite"));
        database[20] = 1;
        Path reservedDatabase = Files.createTempFile("reserved", ".sqlite");
        try {
            Files.write(reservedDatabase, database);
            IOException error = assertThrows(IOException.class,
                    () -> SqliteReader.readTable(reservedDatabase, null, "blobs"));
            assertTrue(error.getMessage().contains("reserved bytes are not supported"));
        } finally {
            Files.deleteIfExists(reservedDatabase);
        }
    }

    @Test
    void rejectsReservedBytesInWalPageOne() throws IOException, URISyntaxException {
        byte[] wal = Files.readAllBytes(resource("sqlite/wal/cookies.sqlite-wal"));
        wal[32 + 24 + 20] = 1;
        rechecksumWal(wal, false);
        Path reservedWal = Files.createTempFile("cookies-reserved", ".wal");
        try {
            Files.write(reservedWal, wal);
            IOException error = assertThrows(IOException.class,
                    () -> SqliteReader.readTable(resource("sqlite/wal/cookies.sqlite"), reservedWal,
                            "moz_cookies"));
            assertTrue(error.getMessage().contains("reserved bytes are not supported"));
        } finally {
            Files.deleteIfExists(reservedWal);
        }
    }

    @Test
    void missingTableIsReported() throws URISyntaxException {
        Path db = resource("sqlite/simple.sqlite");
        assertTrue(assertIoError(() -> SqliteReader.readTable(db, null, "nope")));
    }

    private static void rechecksumWal(byte[] wal, boolean bigEndian) {
        long[] checksum = checksum(wal, 0, 24, 0, 0, bigEndian);
        writeU32(wal, 24, checksum[0]);
        writeU32(wal, 28, checksum[1]);
        int pageSize = (int) readU32(wal, 8);
        int frameSize = 24 + pageSize;
        for (int offset = 32; offset + frameSize <= wal.length; offset += frameSize) {
            checksum = checksum(wal, offset, 8, checksum[0], checksum[1], bigEndian);
            checksum = checksum(wal, offset + 24, pageSize, checksum[0], checksum[1], bigEndian);
            writeU32(wal, offset + 16, checksum[0]);
            writeU32(wal, offset + 20, checksum[1]);
        }
    }

    private static long[] checksum(byte[] data, int offset, int length, long s1, long s2,
            boolean bigEndian) {
        ByteBuffer words = ByteBuffer.wrap(data, offset, length)
                .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int first = (int) s1;
        int second = (int) s2;
        while (words.hasRemaining()) {
            first += words.getInt() + second;
            second += words.getInt() + first;
        }
        return new long[] {first & 0xFFFFFFFFL, second & 0xFFFFFFFFL};
    }

    private static long readU32(byte[] data, int offset) {
        return ByteBuffer.wrap(data).getInt(offset) & 0xFFFFFFFFL;
    }

    private static void writeU32(byte[] data, int offset, long value) {
        ByteBuffer.wrap(data).putInt(offset, (int) value);
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
