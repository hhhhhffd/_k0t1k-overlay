package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal read-only reader for one table of a SQLite file, enough to pull cookies out of a browser
 * profile without a JDBC driver or any native library. It understands only what that needs: the file
 * header, {@code sqlite_schema} on page 1 (to find a table's root page and column order), table b-tree
 * pages (interior {@code 0x05}, leaf {@code 0x0D}), the record format (including overflow pages for big
 * rows), and, since Firefox keeps its cookie database in WAL mode, the write-ahead log overlay. Indexes
 * and every other page type are ignored.
 */
final class SqliteReader {
    private final byte[] file;
    private final int pageSize;
    /** Page number (1-based) to its bytes, once a WAL has been applied; empty for a plain file. */
    private final Map<Integer, byte[]> walPages;
    /** The current read's {@code INTEGER PRIMARY KEY} column, if any; see {@link #readRecord}. */
    private int rowidAliasIndex = -1;

    private SqliteReader(byte[] file, int pageSize, Map<Integer, byte[]> walPages) {
        this.file = file;
        this.pageSize = pageSize;
        this.walPages = walPages;
    }

    /**
     * Reads every row of {@code table} out of {@code dbFile}, applying {@code walFile} on top when it
     * exists (pass null when there is none). Each row is a column name to value map, in the table's
     * declared column order; a text column holds a {@link String}, a blob a {@code byte[]}, an integer
     * a {@link Long}, a real a {@link Double}, and NULL is absent from the map.
     */
    static List<Map<String, Object>> readTable(Path dbFile, Path walFile, String table) throws IOException {
        byte[] file = Files.readAllBytes(dbFile);
        if (file.length < 100 || file[0] != 'S' || file[1] != 'Q') {
            throw new IOException(dbFile + " is not a SQLite file");
        }
        int pageSize = u16(file, 16);
        if (pageSize == 1) {
            pageSize = 65536; // 1 means 65536, the only page size that does not fit in 16 bits
        }
        Map<Integer, byte[]> walPages = walFile != null && Files.exists(walFile)
                ? readWal(Files.readAllBytes(walFile), pageSize)
                : new LinkedHashMap<>();
        SqliteReader reader = new SqliteReader(file, pageSize, walPages);
        return reader.read(table);
    }

    // ---- WAL ----

    /** The newest committed version of each page the WAL holds, keyed by page number. */
    private static Map<Integer, byte[]> readWal(byte[] wal, int dbPageSize) throws IOException {
        Map<Integer, byte[]> pages = new LinkedHashMap<>();
        if (wal.length < 32) {
            return pages;
        }
        long magic = u32(wal, 0);
        if (magic != 0x377f0682L && magic != 0x377f0683L) {
            return pages; // not a WAL file (or empty): nothing to overlay
        }
        boolean bigEndianChecksum = magic == 0x377f0683L;
        int pageSize = (int) u32(wal, 8);
        if (pageSize == 1) {
            pageSize = 65536;
        }
        if (pageSize != dbPageSize) {
            return pages; // mismatched WAL: ignore it rather than corrupt the read
        }
        int salt1 = (int) u32(wal, 16);
        int salt2 = (int) u32(wal, 20);
        int frameSize = 24 + pageSize;
        Map<Integer, byte[]> pending = new LinkedHashMap<>();
        for (int offset = 32; offset + frameSize <= wal.length; offset += frameSize) {
            int pageNumber = (int) u32(wal, offset);
            long dbSizeAfterCommit = u32(wal, offset + 4);
            int frameSalt1 = (int) u32(wal, offset + 8);
            int frameSalt2 = (int) u32(wal, offset + 12);
            if (frameSalt1 != salt1 || frameSalt2 != salt2) {
                continue; // a frame from an earlier WAL generation after a checkpoint reset the salts
            }
            byte[] page = new byte[pageSize];
            System.arraycopy(wal, offset + 24, page, 0, pageSize);
            pending.put(pageNumber, page);
            if (dbSizeAfterCommit != 0) {
                // A committed transaction: everything buffered since the last commit is now visible.
                pages.putAll(pending);
                pending.clear();
            }
        }
        if (bigEndianChecksum) {
            // Checksums are not verified (nothing here signs them against tampering); the byte order
            // bit only tells frames from a plain append, which both orders parse the same way here.
        }
        return pages;
    }

    // ---- schema ----

    private List<Map<String, Object>> read(String table) throws IOException {
        List<Object[]> schemaRows = readPage(1, null);
        int rootPage = -1;
        String sql = null;
        for (Object[] row : schemaRows) {
            // sqlite_schema: type, name, tbl_name, rootpage, sql
            if (row.length >= 5 && "table".equals(row[0]) && table.equals(row[1])) {
                rootPage = ((Number) row[3]).intValue();
                sql = (String) row[4];
            }
        }
        if (rootPage < 0 || sql == null) {
            throw new IOException("no table named " + table);
        }
        Columns columns = parseColumns(sql);
        List<Object[]> rows = new ArrayList<>();
        rowidAliasIndex = columns.rowidAlias;
        collect(rootPage, rows);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> record = new LinkedHashMap<>();
            for (int i = 0; i < columns.names.size() && i < row.length; i++) {
                if (row[i] != null) {
                    record.put(columns.names.get(i), row[i]);
                }
            }
            out.add(record);
        }
        return out;
    }

    /** A table's declared columns, and which one (if any) is the {@code INTEGER PRIMARY KEY} rowid alias. */
    private static final class Columns {
        final List<String> names = new ArrayList<>();
        int rowidAlias = -1;
    }

    /** Column names from a {@code CREATE TABLE(...)} statement, in declared order. */
    private static Columns parseColumns(String sql) {
        Columns columns = new Columns();
        int open = sql.indexOf('(');
        int close = sql.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return columns;
        }
        for (String part : splitTopLevel(sql.substring(open + 1, close))) {
            String def = part.trim();
            if (def.isEmpty()) {
                continue;
            }
            String upper = def.toUpperCase(java.util.Locale.ROOT);
            if (upper.startsWith("PRIMARY KEY") || upper.startsWith("UNIQUE") || upper.startsWith("CHECK")
                    || upper.startsWith("FOREIGN KEY") || upper.startsWith("CONSTRAINT")) {
                continue; // a table constraint, not a column
            }
            String name;
            if (def.startsWith("\"") || def.startsWith("`")) {
                char quote = def.charAt(0);
                int end = def.indexOf(quote, 1);
                name = end > 0 ? def.substring(1, end) : def;
            } else if (def.startsWith("[")) {
                int end = def.indexOf(']');
                name = end > 0 ? def.substring(1, end) : def;
            } else {
                int space = def.indexOf(' ');
                name = space > 0 ? def.substring(0, space) : def;
            }
            // A column declared "INTEGER PRIMARY KEY" does not store its own value: it is another
            // name for the row's key, which the leaf cell carries separately (see readRecord).
            if (upper.matches(".*\\bINTEGER\\b.*\\bPRIMARY\\s+KEY\\b.*")) {
                columns.rowidAlias = columns.names.size();
            }
            columns.names.add(name);
        }
        return columns;
    }

    /** Splits on commas that are not inside parentheses. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    // ---- b-tree traversal ----

    private void collect(int pageNumber, List<Object[]> out) throws IOException {
        for (Object[] row : readPage(pageNumber, out)) {
            out.add(row);
        }
    }

    /**
     * Reads one table b-tree page. For an interior page this recurses into its children and returns
     * nothing itself (rows land in {@code sink} as they are found); for a leaf it returns its rows
     * directly, which also serves the one call ({@code sqlite_schema} on page 1) that wants them back
     * without a sink.
     */
    private List<Object[]> readPage(int pageNumber, List<Object[]> sink) throws IOException {
        byte[] page = page(pageNumber);
        // Page 1 carries the 100-byte file header before its own b-tree header.
        int base = pageNumber == 1 ? 100 : 0;
        int type = page[base] & 0xFF;
        int cellCount = u16(page, base + 3);
        int cellPointerArray = base + (type == 0x02 || type == 0x05 ? 12 : 8);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            int cellOffset = u16(page, cellPointerArray + i * 2);
            if (type == 0x05) {
                // Interior table page: a left-child pointer, then the integer key (unused here).
                int child = (int) u32(page, cellOffset);
                if (sink != null) {
                    collect(child, sink);
                } else {
                    rows.addAll(readPage(child, null));
                }
            } else if (type == 0x0D) {
                rows.add(readRecord(page, cellOffset, pageNumber));
            }
            // Other types (0x02 index interior, 0x0A index leaf) are never a table's root here.
        }
        if (type == 0x05) {
            int rightmost = (int) u32(page, base + 8);
            if (sink != null) {
                collect(rightmost, sink);
            } else {
                rows.addAll(readPage(rightmost, null));
            }
        }
        return rows;
    }

    /** One leaf cell: varint payload length, varint rowid, then the record (with overflow if needed). */
    private Object[] readRecord(byte[] page, int cellOffset, int pageNumber) throws IOException {
        int[] pos = {cellOffset};
        long payloadLength = readVarint(page, pos);
        long rowid = readVarint(page, pos);
        int localStart = pos[0];
        int usable = pageSize; // reserved space at the end of a page is not modelled; unused by these files
        int maxLocal = usable - 35;
        byte[] payload;
        if (payloadLength <= maxLocal) {
            payload = new byte[(int) payloadLength];
            System.arraycopy(page, localStart, payload, 0, (int) payloadLength);
        } else {
            int minLocal = (usable - 12) * 32 / 255 - 23;
            int localSize = minLocal + (int) ((payloadLength - minLocal) % (usable - 4));
            if (localSize > maxLocal) {
                localSize = minLocal;
            }
            payload = new byte[(int) payloadLength];
            System.arraycopy(page, localStart, payload, 0, localSize);
            int next = (int) u32(page, localStart + localSize);
            int filled = localSize;
            while (next != 0) {
                byte[] overflow = page(next);
                int chunk = Math.min(usable - 4, payload.length - filled);
                System.arraycopy(overflow, 4, payload, filled, chunk);
                filled += chunk;
                next = (int) u32(overflow, 0);
            }
        }
        Object[] values = decodeRecord(payload);
        // An INTEGER PRIMARY KEY column is just another name for the rowid: SQLite stores NULL for it
        // in the record itself and keeps the real value only here, in the cell header.
        if (rowidAliasIndex >= 0 && rowidAliasIndex < values.length && values[rowidAliasIndex] == null) {
            values[rowidAliasIndex] = rowid;
        }
        return values;
    }

    /** The record format: a header of serial-type varints, then each value's bytes back to back. */
    private static Object[] decodeRecord(byte[] payload) {
        int[] pos = {0};
        long headerLength = readVarint(payload, pos);
        List<Long> serialTypes = new ArrayList<>();
        while (pos[0] < headerLength) {
            serialTypes.add(readVarint(payload, pos));
        }
        Object[] values = new Object[serialTypes.size()];
        int offset = (int) headerLength;
        for (int i = 0; i < serialTypes.size(); i++) {
            long serial = serialTypes.get(i);
            int size = serialSize(serial);
            values[i] = decodeValue(payload, offset, serial);
            offset += size;
        }
        return values;
    }

    private static int serialSize(long serial) {
        if (serial >= 12) {
            return (int) ((serial - (serial % 2 == 0 ? 12 : 13)) / 2);
        }
        switch ((int) serial) {
            case 0: case 8: case 9: case 10: case 11: return 0;
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 4: return 4;
            case 5: return 6;
            case 6: case 7: return 8;
            default: return 0;
        }
    }

    private static Object decodeValue(byte[] data, int offset, long serial) {
        switch ((int) serial) {
            case 0: return null; // NULL
            case 8: return 0L;
            case 9: return 1L;
            case 1: return (long) data[offset];
            case 2: return (long) (short) ((data[offset] << 8) | (data[offset + 1] & 0xFF));
            case 3: return signedBE(data, offset, 3);
            case 4: return signedBE(data, offset, 4);
            case 5: return signedBE(data, offset, 6);
            case 6: return signedBE(data, offset, 8);
            case 7: return ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
            case 10: case 11: return null; // reserved serial types, never produced by SQLite itself
            default:
                int size = serialSize(serial);
                byte[] bytes = new byte[size];
                System.arraycopy(data, offset, bytes, 0, size);
                return serial % 2 == 0 ? bytes : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static long signedBE(byte[] data, int offset, int length) {
        long value = (data[offset] & 0x80) != 0 ? -1L : 0L; // sign-extend the leading byte
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static long readVarint(byte[] data, int[] pos) {
        long value = 0;
        for (int i = 0; i < 9; i++) {
            byte b = data[pos[0]++];
            if (i == 8) {
                value = (value << 8) | (b & 0xFF);
                break;
            }
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return value;
    }

    private byte[] page(int pageNumber) {
        byte[] overlay = walPages.get(pageNumber);
        if (overlay != null) {
            return overlay;
        }
        byte[] page = new byte[pageSize];
        int offset = (pageNumber - 1) * pageSize;
        int length = Math.min(pageSize, file.length - offset);
        if (length > 0) {
            System.arraycopy(file, offset, page, 0, length);
        }
        return page;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
