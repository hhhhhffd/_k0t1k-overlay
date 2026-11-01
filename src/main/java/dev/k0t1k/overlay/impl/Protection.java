package dev.k0t1k.overlay.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * What the built-in browser does in place of extensions (windowless Chromium cannot load them): blocks
 * ads and trackers, hides page clutter and cookie banners with uBlock Origin's and EasyList's filter
 * lists, patches pages with a few scriptlets, and darkens pages with Dark Reader.
 *
 * <p>Everything is downloaded at run time into {@code <dataDir>/browser/filters} (nothing is bundled),
 * refreshed weekly, size-capped and sanity-checked; a failed download keeps the older file. Chromium
 * calls {@link #blocker()} on its network thread and {@link #scripts()} on its UI thread, so both only
 * read immutable data behind volatile references. Sites can switch each part off ({@code sites.tsv}).
 */
final class Protection {
    /** Per-site switches. */
    static final int ADS = 1;
    static final int COOKIES = 2;
    static final int DARK = 4;

    private static final long WEEK = 7L * 24 * 3600 * 1000;
    private static final int LIST_CAP = 8 << 20;
    private static final int TARBALL_CAP = 4 << 20;
    private static final String UBO = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/";
    private static final String DARK_URL = "https://registry.npmjs.org/darkreader/-/darkreader-4.9.134.tgz";
    private static final String DARK_SHA512 =
            "HAyEz97vK/UFLO6kcjxojji5OKr0+pwgGLrBzyg67xD2p6ySrSyCBvNxb+0KTSMYk9qNz9Hq85JR3TaKyqEV1w==";
    private static final String DARK_FILE = "darkreader-4.9.134.js";

    /** Sent on the loads after the first one of a page, to put back styles the page removed. */
    private static final String REFRESH = "(function(){var P=window.__k0t1kP;if(!P)return;for(var id in P.css){"
            + "if(!document.getElementById(id)){var r=document.head||document.documentElement;if(r){"
            + "var s=document.createElement('style');s.id=id;s.textContent=P.css[id];r.appendChild(s);}}}})();";
    private static final String PRELUDE = "try{(function(){var P=window.__k0t1kP;if(!P){P=window.__k0t1kP={css:{}};"
            + "P.ready=function(f){if(document.documentElement)f();else document.addEventListener('DOMContentLoaded',f);};"
            + "P.put=function(id,t){P.css[id]=t;P.ready(function(){if(document.getElementById(id))return;"
            + "var r=document.head||document.documentElement,s=document.createElement('style');"
            + "s.id=id;s.textContent=t;r.appendChild(s);});};}})();}catch(e){}\n";

    private static final class Source {
        final String name;
        final String url;
        final String file;
        final boolean cookies;

        Source(String name, String url, String file, boolean cookies) {
            this.name = name;
            this.url = url;
            this.file = file;
            this.cookies = cookies;
        }
    }

    private static final Source[] SOURCES = {
        new Source("uBlock filters", UBO + "filters.txt", "ubo-filters.txt", false),
        new Source("uBlock privacy", UBO + "privacy.txt", "ubo-privacy.txt", false),
        new Source("uBlock badware", UBO + "badware.txt", "ubo-badware.txt", false),
        new Source("uBlock quick fixes", UBO + "quick-fixes.txt", "ubo-quick-fixes.txt", false),
        new Source("uBlock unbreak", UBO + "unbreak.txt", "ubo-unbreak.txt", false),
        new Source("EasyList", "https://easylist.to/easylist/easylist.txt", "easylist.txt", false),
        new Source("EasyPrivacy", "https://easylist.to/easylist/easyprivacy.txt", "easyprivacy.txt", false),
        new Source("uBlock cookie notices", UBO + "annoyances-cookies.txt", "ubo-annoyances-cookies.txt", true),
    };

    /** What the screen shows about one list. */
    static final class Info {
        final String name;
        int network;
        int cosmetic;
        int scriptlets;
        int skipped;
        /** Milliseconds since the epoch of the file, 0 when there is none. */
        long updated;
        String error = "";

        Info(String name) {
            this.name = name;
        }
    }

    private static final class Snapshot {
        final NetFilter adsNet;
        final Cosmetic adsCosmetic;
        final NetFilter cookieNet;
        final Cosmetic cookieCosmetic;

        Snapshot(NetFilter adsNet, Cosmetic adsCosmetic, NetFilter cookieNet, Cosmetic cookieCosmetic) {
            this.adsNet = adsNet;
            this.adsCosmetic = adsCosmetic;
            this.cookieNet = cookieNet;
            this.cookieCosmetic = cookieCosmetic;
        }
    }

    private final Options options;
    private final Path dir;
    private final Path sitesFile;
    private final Executor io;
    /** Runs the list updates, which can take long; their rules replace the snapshot when ready. */
    private final Executor updater;
    private final java.util.function.Consumer<Throwable> onError;
    private volatile Snapshot snapshot;
    private volatile String darkReader;
    private volatile Map<String, Integer> sites = Collections.emptyMap();
    private volatile List<Info> infos = Collections.emptyList();
    private volatile boolean updating;
    private boolean started;

    Protection(Options options, Path dataDir, Executor io, Executor updater, java.util.function.Consumer<Throwable> onError) {
        this.options = options;
        this.dir = dataDir.resolve("browser").resolve("filters");
        this.sitesFile = dataDir.resolve("browser").resolve("sites.tsv");
        this.io = io;
        this.updater = updater;
        this.onError = onError;
    }

    /** Loads the stored lists and refreshes the old ones; once, when the browser starts. */
    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        updater.execute(() -> run(false));
    }

    /** The "Update lists now" button. */
    synchronized void updateNow() {
        if (!updating) {
            updating = true;
            updater.execute(() -> run(true));
        }
    }

    boolean updating() {
        return updating;
    }

    List<Info> status() {
        return infos;
    }

    private void run(boolean force) {
        updating = true;
        try {
            Files.createDirectories(dir);
            loadSites();
            loadDarkReader();
            rebuild();
            for (Source source : SOURCES) {
                Path file = dir.resolve(source.file);
                boolean old = !Files.exists(file) || System.currentTimeMillis() - Files.getLastModifiedTime(file).toMillis() > WEEK;
                if (force || old) {
                    download(source, file);
                }
            }
            if (darkReader == null) {
                downloadDarkReader();
            }
            rebuild();
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
        } finally {
            updating = false;
        }
    }

    // ---- lists ----

    private void download(Source source, Path file) {
        Info info = null;
        for (Info i : infos) {
            if (i.name.equals(source.name)) {
                info = i;
            }
        }
        try {
            byte[] data = fetch(source.url, LIST_CAP);
            String text = new String(data, StandardCharsets.UTF_8);
            // Anything else (an error page, a captive portal) must not replace a good list.
            int rules = 0;
            for (int at = text.indexOf("||"); at >= 0 && rules < 50; at = text.indexOf("||", at + 2)) {
                rules++;
            }
            for (int at = text.indexOf("##"); at >= 0 && rules < 100; at = text.indexOf("##", at + 2)) {
                rules++;
            }
            if (rules < 50) {
                throw new IOException("does not look like a filter list");
            }
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.write(part, data);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            if (info != null) {
                info.error = "";
            }
        } catch (IOException e) {
            Overlay.log("filter list " + source.name + " not updated: " + e.getMessage());
            if (info != null) {
                info.error = String.valueOf(e.getMessage());
            }
        }
    }

    private void rebuild() throws IOException {
        NetFilter.Builder adsNet = new NetFilter.Builder();
        Cosmetic.Builder adsCosmetic = new Cosmetic.Builder();
        NetFilter.Builder cookieNet = new NetFilter.Builder();
        Cosmetic.Builder cookieCosmetic = new Cosmetic.Builder();
        List<Info> fresh = new ArrayList<>();
        for (Source source : SOURCES) {
            Info info = new Info(source.name);
            Path file = dir.resolve(source.file);
            for (Info old : infos) {
                if (old.name.equals(source.name)) {
                    info.error = old.error;
                }
            }
            fresh.add(info);
            if (!Files.exists(file)) {
                continue;
            }
            info.updated = Files.getLastModifiedTime(file).toMillis();
            NetFilter.Builder net = source.cookies ? cookieNet : adsNet;
            Cosmetic.Builder cosmetic = source.cookies ? cookieCosmetic : adsCosmetic;
            int rules = net.rules;
            int skipped = net.skipped + cosmetic.skipped;
            int hide = cosmetic.rules;
            int scripts = cosmetic.scripts;
            for (String line : new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n")) {
                if (line.indexOf('#') < 0 || !cosmetic.add(line)) {
                    net.add(line);
                }
            }
            info.network = net.rules - rules;
            info.cosmetic = cosmetic.rules - hide;
            info.scriptlets = cosmetic.scripts - scripts;
            info.skipped = net.skipped + cosmetic.skipped - skipped;
        }
        snapshot = new Snapshot(adsNet.build(), adsCosmetic.build(), cookieNet.build(), cookieCosmetic.build());
        infos = fresh;
    }

    /** One byte array of at most {@code cap} bytes from an https address. */
    private static byte[] fetch(String address, int cap) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "k0t1k-overlay");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("answered " + connection.getResponseCode());
            }
            if (connection.getContentLengthLong() > cap) {
                throw new IOException("larger than " + cap / 1024 + " KB");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            try (InputStream in = connection.getInputStream()) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    if (out.size() > cap) {
                        throw new IOException("larger than " + cap / 1024 + " KB");
                    }
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    // ---- Dark Reader ----

    private void loadDarkReader() throws IOException {
        Path file = dir.resolve(DARK_FILE);
        if (Files.exists(file)) {
            darkReader = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
    }

    /** Downloads the npm tarball, checks npm's integrity hash and unpacks the one file wanted. */
    private void downloadDarkReader() {
        try {
            byte[] tgz = fetch(DARK_URL, TARBALL_CAP);
            String hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(tgz));
            if (!hash.equals(DARK_SHA512)) {
                throw new IOException("Dark Reader does not match its pinned hash");
            }
            byte[] script = untar(new GZIPInputStream(new ByteArrayInputStream(tgz)), "package/darkreader.js", 8 << 20);
            Path part = dir.resolve(DARK_FILE + ".part");
            Files.write(part, script);
            Files.move(part, dir.resolve(DARK_FILE), StandardCopyOption.REPLACE_EXISTING);
            darkReader = new String(script, StandardCharsets.UTF_8);
        } catch (IOException | NoSuchAlgorithmException e) {
            Overlay.log("Dark Reader not installed: " + e.getMessage());
        }
    }

    /** Reads {@code wanted} out of a tar stream; every entry name is checked, so a bad archive is refused. */
    private static byte[] untar(InputStream in, String wanted, int cap) throws IOException {
        byte[] header = new byte[512];
        byte[] found = null;
        while (readFully(in, header)) {
            if (header[0] == 0) {
                break;
            }
            String name = text(header, 0, 100);
            String prefix = text(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            if (name.startsWith("/") || name.contains("..") || name.indexOf('\\') >= 0) {
                throw new IOException("unsafe path in archive");
            }
            long size = Long.parseLong(text(header, 124, 12).trim().isEmpty() ? "0" : text(header, 124, 12).trim(), 8);
            long padded = (size + 511) / 512 * 512;
            if (name.equals(wanted) && (header[156] == '0' || header[156] == 0)) {
                if (size > cap) {
                    throw new IOException("file in archive is too large");
                }
                found = new byte[(int) size];
                if (!readFully(in, found)) {
                    throw new IOException("archive is cut off");
                }
                skip(in, padded - size);
            } else {
                skip(in, padded);
            }
        }
        if (found == null) {
            throw new IOException(wanted + " is not in the archive");
        }
        return found;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                if (read == 0) {
                    return false;
                }
                throw new IOException("archive is cut off");
            }
            read += n;
        }
        return true;
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        byte[] buffer = new byte[8192];
        for (long left = bytes; left > 0; ) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                throw new IOException("archive is cut off");
            }
            left -= n;
        }
    }

    private static String text(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    // ---- sites ----

    private static String siteKey(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    boolean siteOn(String host, int feature) {
        Integer off = sites.get(siteKey(host));
        return off == null || (off & feature) == 0;
    }

    /** Switches one part for a site and saves the list of exceptions. */
    synchronized void toggleSite(String host, int feature) {
        String key = siteKey(host);
        Map<String, Integer> next = new HashMap<>(sites);
        int off = next.getOrDefault(key, 0) ^ feature;
        if (off == 0) {
            next.remove(key);
        } else {
            next.put(key, off);
        }
        sites = next;
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : next.entrySet()) {
            List<String> names = new ArrayList<>();
            if ((e.getValue() & ADS) != 0) {
                names.add("ads");
            }
            if ((e.getValue() & COOKIES) != 0) {
                names.add("cookies");
            }
            if ((e.getValue() & DARK) != 0) {
                names.add("dark");
            }
            lines.add(e.getKey() + "\t" + String.join(",", names));
        }
        io.execute(() -> {
            try {
                Files.createDirectories(sitesFile.getParent());
                Path part = sitesFile.resolveSibling("sites.tsv.part");
                Files.write(part, lines, StandardCharsets.UTF_8);
                Files.move(part, sitesFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                onError.accept(e);
            }
        });
    }

    private void loadSites() throws IOException {
        Map<String, Integer> loaded = new HashMap<>();
        if (Files.exists(sitesFile)) {
            for (String line : Files.readAllLines(sitesFile, StandardCharsets.UTF_8)) {
                String[] cells = line.split("\t");
                if (cells.length < 2) {
                    continue;
                }
                int off = 0;
                for (String name : cells[1].split(",")) {
                    off |= name.equals("ads") ? ADS : name.equals("cookies") ? COOKIES : name.equals("dark") ? DARK : 0;
                }
                if (off != 0) {
                    loaded.put(cells[0], off);
                }
            }
        }
        sites = loaded;
    }

    // ---- what Chromium asks ----

    /**
     * For Chromium's network thread: {request address, resource type name, address of the top-level page,
     * address of the document making the request} to whether the request is cancelled. The shield and
     * per-site switches follow the top page; {@code domain=} and third-party tests the document.
     */
    Predicate<String[]> blocker() {
        return request -> {
            Snapshot s = snapshot;
            if (s == null) {
                return false;
            }
            String page = NetFilter.host(request[2]);
            String document = NetFilter.host(request[3]);
            int type = NetFilter.typeOfRequest(request[1]);
            if (options.protectBlock && siteOn(page, ADS) && !s.adsNet.documentOff(page)
                    && s.adsNet.blocks(request[0], type, document)) {
                return true;
            }
            return options.protectCookies && siteOn(page, COOKIES) && !s.cookieNet.documentOff(page)
                    && s.cookieNet.blocks(request[0], type, document);
        };
    }

    /**
     * For Chromium's UI thread: (page address, whether the page just started loading) to the script to
     * run in it, "" for none. The full script is for a new document; later calls only restore styles.
     */
    BiFunction<String, Boolean, String> scripts() {
        return (url, full) -> {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "";
            }
            return full ? pageScript(NetFilter.host(url)) : REFRESH;
        };
    }

    private String pageScript(String host) {
        Snapshot s = snapshot;
        boolean ads = options.protectCosmetic && siteOn(host, ADS);
        boolean cookies = options.protectCookies && siteOn(host, COOKIES);
        boolean youtube = options.protectYoutube && siteOn(host, ADS) && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
        String dark = options.protectDark && siteOn(host, DARK) ? darkReader : null;
        StringBuilder js = new StringBuilder(PRELUDE);
        boolean scriptlets = false;
        StringBuilder calls = new StringBuilder();
        if (s != null) {
            if (ads && !s.adsNet.hideOff(host)) {
                String css = s.adsCosmetic.css(host, !s.adsNet.genericOff(host));
                if (!css.isEmpty()) {
                    js.append("try{window.__k0t1kP.put('k0t1k-ads',").append(quote(css)).append(");}catch(e){}\n");
                }
            }
            if (cookies && !s.cookieNet.hideOff(host)) {
                String css = s.cookieCosmetic.css(host, !s.cookieNet.genericOff(host));
                if (!css.isEmpty()) {
                    js.append("try{window.__k0t1kP.put('k0t1k-cookies',").append(quote(css)).append(");}catch(e){}\n");
                    js.append("try{").append(resource("page-scroll.js")).append("}catch(e){}\n");
                }
            }
            List<String[]> found = new ArrayList<>();
            if (ads) {
                found.addAll(s.adsCosmetic.scriptlets(host));
            }
            if (cookies) {
                found.addAll(s.cookieCosmetic.scriptlets(host));
            }
            for (String[] call : found) {
                scriptlets = true;
                calls.append("P.scriptlet(").append(quote(call[0])).append(",[");
                for (int i = 1; i < call.length; i++) {
                    calls.append(i > 1 ? "," : "").append(quote(call[i]));
                }
                calls.append("]);\n");
            }
        }
        if (scriptlets) {
            js.append("try{(function(P){").append(resource("page-scriptlets.js")).append('\n').append(calls)
                    .append("})(window.__k0t1kP);}catch(e){}\n");
        }
        if (youtube) {
            js.append("try{").append(resource("page-youtube.js")).append("}catch(e){}\n");
        }
        if (dark != null) {
            js.append(dark).append("\n;try{window.__k0t1kP.ready(function(){DarkReader.setFetchMethod(window.fetch);"
                    + "DarkReader.enable({brightness:100,contrast:90,sepia:10});});}catch(e){}\n");
        }
        return js.length() == PRELUDE.length() ? "" : js.toString();
    }

    private static final Map<String, String> RESOURCES = new java.util.concurrent.ConcurrentHashMap<>();

    private static String resource(String name) {
        return RESOURCES.computeIfAbsent(name, n -> {
            try (InputStream in = Protection.class.getResourceAsStream(n)) {
                if (in == null) {
                    throw new IllegalStateException(n + " is missing from the overlay jar");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** A JavaScript string literal. */
    static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case ' ': out.append("\\u2028"); break;
                case ' ': out.append("\\u2029"); break;
                default: out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** "2026-10-31" for the status lines. */
    static String date(long millis) {
        return millis == 0 ? "-" : String.format(Locale.ROOT, "%tF", millis);
    }
}
