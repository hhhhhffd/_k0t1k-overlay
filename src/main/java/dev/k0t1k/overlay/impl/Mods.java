package dev.k0t1k.overlay.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The instance's mods, read from the jars in {@code <gameDir>/mods}: name, version and description from
 * {@code fabric.mod.json}, {@code quilt.mod.json} or {@code META-INF/(neoforge.)mods.toml}. Scanned on
 * the io thread when the mods tab first opens.
 */
final class Mods {
    static final class Mod {
        final String file;
        final boolean enabled;
        String id = "";
        String name = "";
        String version = "";
        String description = "";
        String homepage = "";

        Mod(String file, boolean enabled) {
            this.file = file;
            this.enabled = enabled;
        }
    }

    private final Path folder;
    private final Executor io;
    private final Executor render;
    private final Consumer<Throwable> onError;
    /** Sorted by name; replaced as a whole on the render thread when a scan finishes. */
    List<Mod> mods = new ArrayList<>();
    boolean scanned;
    private boolean scanning;

    Mods(Path gameDir, Executor io, Executor render, Consumer<Throwable> onError) {
        this.folder = gameDir.resolve("mods");
        this.io = io;
        this.render = render;
        this.onError = onError;
    }

    void scan() {
        if (scanning) {
            return;
        }
        scanning = true;
        io.execute(() -> {
            List<Mod> found = new ArrayList<>();
            try {
                if (Files.isDirectory(folder)) {
                    try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
                        for (Path file : files) {
                            String name = file.getFileName().toString();
                            boolean enabled = name.endsWith(".jar");
                            if (enabled || name.endsWith(".jar.disabled")) {
                                found.add(read(file, enabled));
                            }
                        }
                    }
                }
            } catch (IOException e) {
                onError.accept(e);
            }
            found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            render.execute(() -> {
                mods = found;
                scanned = true;
                scanning = false;
            });
        });
    }

    private static Mod read(Path file, boolean enabled) {
        Mod mod = new Mod(file.getFileName().toString(), enabled);
        try (ZipFile zip = new ZipFile(file.toFile())) {
            String fabric = entry(zip, "fabric.mod.json");
            String quilt = fabric == null ? entry(zip, "quilt.mod.json") : null;
            String toml = fabric == null && quilt == null ? entry(zip, "META-INF/neoforge.mods.toml") : null;
            if (toml == null && fabric == null && quilt == null) {
                toml = entry(zip, "META-INF/mods.toml");
            }
            if (fabric != null) {
                Object json = Json.parse(fabric);
                mod.id = Json.string(json, "id");
                mod.name = Json.string(json, "name");
                mod.version = Json.string(json, "version");
                mod.description = Json.string(json, "description");
                mod.homepage = Json.string(Json.get(json, "contact"), "homepage");
            } else if (quilt != null) {
                Object loader = Json.get(Json.parse(quilt), "quilt_loader");
                Object meta = Json.get(loader, "metadata");
                mod.id = Json.string(loader, "id");
                mod.version = Json.string(loader, "version");
                mod.name = Json.string(meta, "name");
                mod.description = Json.string(meta, "description");
                mod.homepage = Json.string(Json.get(meta, "contact"), "homepage");
            } else if (toml != null) {
                mod.id = tomlValue(toml, "modId");
                mod.name = tomlValue(toml, "displayName");
                mod.version = tomlValue(toml, "version");
                mod.description = tomlValue(toml, "description");
                mod.homepage = tomlValue(toml, "displayURL");
            }
        } catch (IOException | RuntimeException e) {
            // A broken or unusual jar still shows with its file name.
        }
        if (mod.version.startsWith("$")) {
            mod.version = "";
        }
        if (mod.name.isEmpty()) {
            mod.name = mod.id.isEmpty() ? mod.file.replaceAll("\\.jar(\\.disabled)?$", "") : mod.id;
        }
        mod.description = mod.description.trim();
        return mod;
    }

    private static String entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The first {@code key = "..."} or {@code key = '''...'''} in a mods.toml; enough for display. */
    private static String tomlValue(String toml, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + key + "\\s*=\\s*(?:'''([\\s\\S]*?)'''|\"\"\"([\\s\\S]*?)\"\"\"|\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)')")
                .matcher(toml);
        if (!m.find()) {
            return "";
        }
        for (int g = 1; g <= 4; g++) {
            if (m.group(g) != null) {
                return g == 3 ? m.group(g).replace("\\\"", "\"").replace("\\n", "\n") : m.group(g);
            }
        }
        return "";
    }

    /** Whether the mod matches a search typed by the player. */
    static boolean matches(Mod mod, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return mod.name.toLowerCase(Locale.ROOT).contains(q) || mod.id.toLowerCase(Locale.ROOT).contains(q)
                || mod.description.toLowerCase(Locale.ROOT).contains(q) || mod.file.toLowerCase(Locale.ROOT).contains(q);
    }

    /** A minimal JSON reader for mod metadata: objects, arrays, strings, numbers, literals. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            json.skip();
            return json.value();
        }

        static Object get(Object object, String key) {
            return object instanceof Map ? ((Map<?, ?>) object).get(key) : null;
        }

        /** A string member, or "" when missing or not a string (fabric also allows arrays for some). */
        static String string(Object object, String key) {
            Object value = get(object, key);
            return value instanceof String ? (String) value : "";
        }

        private Object value() {
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                i++;
                skip();
                while (s.charAt(i) != '}') {
                    String key = string();
                    skip();
                    expect(':');
                    skip();
                    map.put(key, value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        skip();
                    }
                }
                i++;
                return map;
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                i++;
                skip();
                while (s.charAt(i) != ']') {
                    list.add(value());
                    skip();
                    if (s.charAt(i) == ',') {
                        i++;
                        skip();
                    }
                }
                i++;
                return list;
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \t\r\n".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i);
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'r':
                        break;
                    case 'u':
                        out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default:
                        out.append(e);
                }
            }
            i++;
            return out.toString();
        }

        private void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + i);
            }
            i++;
        }

        private void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '﻿') {
                    i++;
                } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    // fabric.mod.json is read leniently; some mods ship comments.
                    while (i < s.length() && s.charAt(i) != '\n') {
                        i++;
                    }
                } else {
                    return;
                }
            }
        }
    }
}
