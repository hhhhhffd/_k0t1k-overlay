package dev.k0t1k.overlay.impl;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

/**
 * Site icons for the tabs and the bookmarks bar: decoded once, scaled to {@link #CELL} pixels,
 * cached as {@code browser/favicons/<host>.png} so the bookmarks have icons before any page loads.
 * Downloads run on a thread of their own because a slow site must not hold up the other io work.
 */
final class Favicons {
    static final int CELL = 32;
    private static final int MAX_BYTES = 256 * 1024;
    private static final int TIMEOUT_MS = 6000;

    private final Path dir;
    private final java.util.function.Consumer<Throwable> onError;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "k0t1k-favicons");
        thread.setDaemon(true);
        return thread;
    });
    /** Guarded by this. Append-only, so a host keeps its index (its slot in the picture). */
    private final List<String> hosts = new ArrayList<>();
    private final List<int[]> pixels = new ArrayList<>();
    private final Set<String> asked = new HashSet<>();
    private final Set<String> diskChecked = new HashSet<>();
    private final Set<String> fetched = new HashSet<>();
    private int stamp;

    Favicons(Path dataDir, java.util.function.Consumer<Throwable> onError) {
        this.dir = dataDir.resolve("browser").resolve("favicons");
        this.onError = onError;
    }

    /** Changes whenever an icon is added or replaced. */
    synchronized int stamp() {
        return stamp;
    }

    synchronized int count() {
        return hosts.size();
    }

    /** The slot of the host's icon, or -1 while there is none. */
    synchronized int slot(String host) {
        return hosts.indexOf(host);
    }

    /** ARGB (straight alpha) {@link #CELL} squared of a slot. */
    synchronized int[] pixels(int slot) {
        return pixels.get(slot);
    }

    /** Starts loading the cached icon of a host the first time it is asked for. */
    void want(String host) {
        synchronized (this) {
            if (host.isEmpty() || !asked.add(host)) {
                return;
            }
        }
        worker.execute(() -> {
            try {
                Path file = file(host);
                if (Files.exists(file)) {
                    BufferedImage image = ImageIO.read(file.toFile());
                    if (image != null) {
                        put(host, scale(image));
                    }
                }
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
            } finally {
                synchronized (this) {
                    diskChecked.add(host);
                }
            }
        });
    }

    /**
     * Downloads the icon a page declared, or when it declared none and there is no icon yet, the
     * conventional {@code /favicon.ico}. Each address is tried once per run.
     */
    void page(String host, String declared, boolean loading) {
        String address = declared;
        synchronized (this) {
            if (host.isEmpty() || !diskChecked.contains(host)) {
                return;
            }
            if (address.isEmpty()) {
                if (loading || hosts.contains(host)) {
                    return;
                }
                address = "https://" + host + "/favicon.ico";
            }
            if (!fetched.add(address)) {
                return;
            }
        }
        String source = address;
        worker.execute(() -> {
            try {
                BufferedImage icon = decode(download(source));
                if (icon != null) {
                    int[] cell = scale(icon);
                    put(host, cell);
                    Files.createDirectories(dir);
                    BufferedImage out = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
                    out.setRGB(0, 0, CELL, CELL, cell, 0, CELL);
                    ImageIO.write(out, "png", file(host).toFile());
                }
            } catch (IOException | RuntimeException e) {
                // A site without a usable icon is normal; the letter stays.
            }
        });
    }

    private Path file(String host) {
        return dir.resolve(host.replaceAll("[^A-Za-z0-9.-]", "_") + ".png");
    }

    private synchronized void put(String host, int[] cell) {
        int at = hosts.indexOf(host);
        if (at < 0) {
            hosts.add(host);
            pixels.add(cell);
        } else {
            pixels.set(at, cell);
        }
        stamp++;
    }

    /** Fetches at most {@link #MAX_BYTES} from a public http(s) address, following a few redirects. */
    private static byte[] download(String address) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop < 4; hop++) {
            String protocol = url.getProtocol();
            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IOException("not a web address");
            }
            // A page picks this address; keep it from probing the player's own machine or network.
            InetAddress target = InetAddress.getByName(url.getHost());
            if (target.isAnyLocalAddress() || target.isLoopbackAddress() || target.isLinkLocalAddress()
                    || target.isSiteLocalAddress()) {
                throw new IOException("not a public address");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new IOException("redirect without a location");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code != 200 || connection.getContentLengthLong() > MAX_BYTES) {
                    throw new IOException("status " + code);
                }
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        out.write(chunk, 0, n);
                        if (out.size() > MAX_BYTES) {
                            throw new IOException("too large");
                        }
                    }
                    return out.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** PNG, JPEG, GIF through ImageIO, or an ICO file; null for anything else (SVG, WebP). */
    static BufferedImage decode(byte[] data) throws IOException {
        if (data.length > 6 && data[0] == 0 && data[1] == 0 && data[2] == 1 && data[3] == 0) {
            return ico(data);
        }
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    /** Picks the largest entry up to {@link #CELL} pixels, else the smallest bigger one. */
    private static BufferedImage ico(byte[] data) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = in.getShort(4) & 0xffff;
        int best = -1;
        int bestSize = 0;
        for (int i = 0; i < count && 6 + i * 16 + 16 <= data.length; i++) {
            int size = in.get(6 + i * 16) & 0xff;
            size = size == 0 ? 256 : size;
            boolean better = best < 0 || (size <= CELL ? (bestSize > CELL || size > bestSize) : (bestSize > CELL && size < bestSize));
            if (better) {
                best = i;
                bestSize = size;
            }
        }
        if (best < 0) {
            return null;
        }
        int length = in.getInt(6 + best * 16 + 8);
        int offset = in.getInt(6 + best * 16 + 12);
        if (offset < 0 || length <= 0 || offset + length > data.length) {
            throw new IOException("broken ico");
        }
        byte[] entry = java.util.Arrays.copyOfRange(data, offset, offset + length);
        if (entry.length > 8 && (entry[0] & 0xff) == 0x89 && entry[1] == 'P') {
            return ImageIO.read(new ByteArrayInputStream(entry));
        }
        return dib(ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN));
    }

    /** A BMP inside an ICO: a bitmap header, the colours, then a 1 bit mask; the height counts both. */
    private static BufferedImage dib(ByteBuffer b) throws IOException {
        int header = b.getInt(0);
        int w = b.getInt(4);
        int h = b.getInt(8) / 2;
        int bits = b.getShort(14) & 0xffff;
        int colors = b.getInt(32);
        if (header < 40 || w <= 0 || w > 256 || h <= 0 || h > 256 || b.getInt(16) != 0
                || (bits != 1 && bits != 4 && bits != 8 && bits != 24 && bits != 32)) {
            throw new IOException("unsupported ico bitmap");
        }
        int paletteSize = bits <= 8 ? (colors == 0 ? 1 << bits : colors) : 0;
        int palette = header;
        int rows = palette + paletteSize * 4;
        int stride = (w * bits + 31) / 32 * 4;
        int maskStride = (w + 31) / 32 * 4;
        int masks = rows + stride * h;
        if (masks + maskStride * h > b.limit()) {
            throw new IOException("short ico bitmap");
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        boolean anyAlpha = false;
        if (bits == 32) {
            for (int y = 0; y < h && !anyAlpha; y++) {
                for (int x = 0; x < w; x++) {
                    if ((b.get(rows + y * stride + x * 4 + 3) & 0xff) != 0) {
                        anyAlpha = true;
                        break;
                    }
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int row = rows + (h - 1 - y) * stride;
            for (int x = 0; x < w; x++) {
                int argb;
                if (bits == 32) {
                    argb = b.getInt(row + x * 4);
                } else if (bits == 24) {
                    argb = 0xff000000 | (b.get(row + x * 3 + 2) & 0xff) << 16 | (b.get(row + x * 3 + 1) & 0xff) << 8
                            | (b.get(row + x * 3) & 0xff);
                } else {
                    int index;
                    if (bits == 8) {
                        index = b.get(row + x) & 0xff;
                    } else if (bits == 4) {
                        index = (b.get(row + x / 2) >> (x % 2 == 0 ? 4 : 0)) & 0xf;
                    } else {
                        index = (b.get(row + x / 8) >> (7 - x % 8)) & 1;
                    }
                    argb = index < paletteSize ? 0xff000000 | b.getInt(palette + index * 4) & 0xffffff : 0;
                }
                if (bits != 32 || !anyAlpha) {
                    boolean masked = (b.get(masks + (h - 1 - y) * maskStride + x / 8) >> (7 - x % 8) & 1) != 0;
                    argb = masked ? 0 : argb | 0xff000000;
                }
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    /** Fits the picture into a {@link #CELL} square, centred, keeping its proportions. */
    private static int[] scale(BufferedImage source) {
        BufferedImage cell = new BufferedImage(CELL, CELL, BufferedImage.TYPE_INT_ARGB);
        double factor = Math.min((double) CELL / source.getWidth(), (double) CELL / source.getHeight());
        int w = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int h = Math.max(1, (int) Math.round(source.getHeight() * factor));
        Graphics2D g = cell.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, (CELL - w) / 2, (CELL - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        return cell.getRGB(0, 0, CELL, CELL, null, 0, CELL);
    }
}
