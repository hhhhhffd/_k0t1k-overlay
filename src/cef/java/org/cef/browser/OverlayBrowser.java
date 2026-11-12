package org.cef.browser;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

/**
 * An off-screen browser that keeps its pixels in memory instead of painting into an AWT canvas, so a
 * game can draw them. Lives in JCEF's package because the browser base class is package-private.
 */
public final class OverlayBrowser extends CefBrowser_N implements CefRenderHandler {
    /** Source for the AWT input events JCEF wants; never shown, safe in headless mode. */
    private static final Component SOURCE = new Component() {
    };

    private final Object lock = new Object();
    /** The view in physical pixels. */
    private int width = 800;
    private int height = 600;
    private double scale = 1;
    private ByteBuffer pixels;
    private int pixelsWidth;
    private int pixelsHeight;
    private long version;
    /** Rows of {@link #pixels} painted since the last {@link #copyFrame}, {@code [top, bottom)}; empty when equal. */
    private int dirtyTop;
    private int dirtyBottom;
    /** The version {@link #copyFrame} last handed out, and the size it had. */
    private long delivered;
    private int deliveredWidth;
    private int deliveredHeight;
    private boolean created;
    private boolean settingFocus;

    public OverlayBrowser(CefClient client, String url, CefRequestContext context) {
        super(client, url, context, null, null, new CefBrowserSettings());
    }

    /** Creates the native browser; call once CEF runs. */
    @Override
    public void createImmediately() {
        if (!created) {
            created = true;
            createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
        }
    }

    /** The device scale factor; CEF reads it through {@link #getScreenInfo} when the view is resized. */
    public void scale(double factor) {
        if (factor > 0 && factor != scale) {
            scale = factor;
            if (created) {
                wasResized(width, height);
            }
        }
    }

    public void resize(int w, int h) {
        if (w <= 0 || h <= 0 || (w == width && h == height)) {
            return;
        }
        width = w;
        height = h;
        wasResized(w, h);
    }

    /**
     * Copies the newest frame into {@code out} if it is newer than {@code seen} and returns its version.
     * Fills {@code size} with the width and height, and {@code size[2..3]} with the rows of {@code out}
     * that were written, {@code [top, bottom)}. When {@code out} already holds the frame handed out last
     * ({@code seen} is its version) only the rows painted since are copied. Returns -1 when {@code out}
     * is too small for the frame.
     */
    public long copyFrame(long seen, ByteBuffer out, int[] size) {
        synchronized (lock) {
            if (pixels == null || version == seen) {
                return seen;
            }
            int w = pixelsWidth;
            int h = pixelsHeight;
            size[0] = w;
            size[1] = h;
            if (out.capacity() < pixels.limit()) {
                return -1;
            }
            boolean partial = seen != 0 && seen == delivered && w == deliveredWidth && h == deliveredHeight;
            int top = partial ? dirtyTop : 0;
            int bottom = partial ? dirtyBottom : h;
            copyRows(pixels, out, w, top, bottom);
            out.position(0).limit(w * h * 4);
            size[2] = top;
            size[3] = bottom;
            dirtyTop = dirtyBottom = 0;
            delivered = version;
            deliveredWidth = w;
            deliveredHeight = h;
            return version;
        }
    }

    /** Copies whole rows {@code [top, bottom)} of a {@code w} pixels wide BGRA picture; positions are left alone. */
    private static void copyRows(ByteBuffer from, ByteBuffer to, int w, int top, int bottom) {
        if (bottom <= top) {
            return;
        }
        ByteBuffer source = from.duplicate();
        source.limit(bottom * w * 4).position(top * w * 4);
        ByteBuffer target = to.duplicate();
        target.clear();
        target.position(top * w * 4);
        target.put(source);
    }

    public void mouse(int id, int x, int y, int modifiers, int clicks, int button) {
        sendMouseEvent(new MouseEvent(SOURCE, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button));
    }

    /** {@code lines} positive scrolls down. */
    public void wheel(int x, int y, int modifiers, int lines) {
        // JCEF hands CEF getUnitsToScroll() (scroll amount times rotation) as pixels, where positive
        // scrolls up; one line of a desktop mouse wheel is about PIXELS_PER_LINE.
        sendMouseWheelEvent(new MouseWheelEvent(SOURCE, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), modifiers,
                x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, PIXELS_PER_LINE, -lines));
    }

    private static final int PIXELS_PER_LINE = 100;

    public void key(int id, int keyCode, char keyChar, int modifiers) {
        char chr = keyChar;
        if (LINUX && id != KeyEvent.KEY_TYPED && chr == KeyEvent.CHAR_UNDEFINED) {
            // On Linux JCEF takes the key from the key code only for a few keys and from the character
            // for the rest, so these ones are passed as the X keysym that Chromium maps back.
            chr = keysym(keyCode, chr);
        }
        KeyEvent event = new KeyEvent(SOURCE, id, System.currentTimeMillis(), modifiers, keyCode, chr);
        if (WINDOWS && id != KeyEvent.KEY_TYPED) {
            setScancode(event, scancode(keyCode));
        }
        sendKeyEvent(event);
    }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux");
    // sun.misc.Unsafe by reflection: --release 8 hides it from the compiler, and no --add-opens is needed.
    private static final Object UNSAFE;
    private static final java.lang.reflect.Method PUT_LONG;
    private static final long SCANCODE_OFFSET;

    static {
        Object unsafe = null;
        java.lang.reflect.Method putLong = null;
        long offset = -1;
        try {
            Class<?> type = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field theUnsafe = type.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            offset = (Long) type.getMethod("objectFieldOffset", java.lang.reflect.Field.class)
                    .invoke(unsafe, KeyEvent.class.getDeclaredField("scancode"));
            putLong = type.getMethod("putLong", Object.class, long.class, long.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[k0t1k-overlay] browser: cannot set key scancodes (" + e + ")");
            unsafe = null;
        }
        UNSAFE = unsafe;
        PUT_LONG = putLong;
        SCANCODE_OFFSET = offset;
    }

    /**
     * On Windows the native side ignores the key code and maps KeyEvent's private {@code scancode} to a
     * virtual key; events built by hand have none, so Backspace, Delete and the arrows would do nothing.
     */
    private static void setScancode(KeyEvent event, int scancode) {
        if (UNSAFE == null || scancode == 0) {
            return;
        }
        try {
            PUT_LONG.invoke(UNSAFE, event, SCANCODE_OFFSET, (long) scancode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set the scancode", e);
        }
    }


    /** Set-1 make codes (extended keys without their E0 prefix) of the AWT key codes the browser sends. */
    private static int scancode(int vk) {
        final String top = "QWERTYUIOP";
        final String home = "ASDFGHJKL";
        final String bottom = "ZXCVBNM";
        if (vk >= KeyEvent.VK_A && vk <= KeyEvent.VK_Z) {
            char c = (char) vk;
            int i = top.indexOf(c);
            if (i >= 0) {
                return 0x10 + i;
            }
            i = home.indexOf(c);
            return i >= 0 ? 0x1E + i : 0x2C + bottom.indexOf(c);
        }
        if (vk >= KeyEvent.VK_1 && vk <= KeyEvent.VK_9) {
            return 0x02 + vk - KeyEvent.VK_1;
        }
        if (vk >= KeyEvent.VK_F1 && vk <= KeyEvent.VK_F10) {
            return 0x3B + vk - KeyEvent.VK_F1;
        }
        switch (vk) {
            case KeyEvent.VK_ESCAPE: return 0x01;
            case KeyEvent.VK_0: return 0x0B;
            case KeyEvent.VK_MINUS: return 0x0C;
            case KeyEvent.VK_EQUALS: return 0x0D;
            case KeyEvent.VK_BACK_SPACE: return 0x0E;
            case KeyEvent.VK_TAB: return 0x0F;
            case KeyEvent.VK_OPEN_BRACKET: return 0x1A;
            case KeyEvent.VK_CLOSE_BRACKET: return 0x1B;
            case KeyEvent.VK_ENTER: return 0x1C;
            case KeyEvent.VK_SEMICOLON: return 0x27;
            case KeyEvent.VK_QUOTE: return 0x28;
            case KeyEvent.VK_BACK_QUOTE: return 0x29;
            case KeyEvent.VK_BACK_SLASH: return 0x2B;
            case KeyEvent.VK_COMMA: return 0x33;
            case KeyEvent.VK_PERIOD: return 0x34;
            case KeyEvent.VK_SLASH: return 0x35;
            case KeyEvent.VK_SPACE: return 0x39;
            case KeyEvent.VK_F11: return 0x57;
            case KeyEvent.VK_F12: return 0x58;
            case KeyEvent.VK_HOME: return 0x47;
            case KeyEvent.VK_UP: return 0x48;
            case KeyEvent.VK_PAGE_UP: return 0x49;
            case KeyEvent.VK_LEFT: return 0x4B;
            case KeyEvent.VK_RIGHT: return 0x4D;
            case KeyEvent.VK_END: return 0x4F;
            case KeyEvent.VK_DOWN: return 0x50;
            case KeyEvent.VK_PAGE_DOWN: return 0x51;
            case KeyEvent.VK_INSERT: return 0x52;
            case KeyEvent.VK_DELETE: return 0x53;
            default: return 0;
        }
    }

    /** X keysyms of the keys JCEF reads from the character on Linux. */
    private static char keysym(int vk, char fallback) {
        if (vk >= KeyEvent.VK_F1 && vk <= KeyEvent.VK_F12) {
            return (char) (0xFFBE + vk - KeyEvent.VK_F1);
        }
        switch (vk) {
            case KeyEvent.VK_HOME: return 0xFF50;
            case KeyEvent.VK_PAGE_UP: return 0xFF55;
            case KeyEvent.VK_PAGE_DOWN: return 0xFF56;
            case KeyEvent.VK_END: return 0xFF57;
            case KeyEvent.VK_INSERT: return 0xFF63;
            default: return fallback;
        }
    }

    /**
     * CefClient.onGotFocus calls setFocus(true), and off screen CEF answers SetFocus with another
     * OnGotFocus on the same stack, which recurses until StackOverflowError; break the cycle here.
     */
    @Override
    public void setFocus(boolean enable) {
        if (settingFocus) {
            return;
        }
        settingFocus = true;
        try {
            super.setFocus(enable);
        } finally {
            settingFocus = false;
        }
    }

    // ---- CefRenderHandler ----

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return viewRect();
    }

    /** The view in device independent pixels, rounded up so CEF paints at least the physical size. */
    private Rectangle viewRect() {
        return new Rectangle(0, 0, (int) Math.ceil(width / scale), (int) Math.ceil(height / scale));
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(scale, 32, 8, false, viewRect(), viewRect());
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return viewPoint;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
    }

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirty, ByteBuffer buffer, int w, int h) {
        if (popup) {
            return;
        }
        int bytes = w * h * 4;
        if (buffer.capacity() < bytes) {
            return; // not a whole frame; never seen, but a short copy would leave stale rows
        }
        synchronized (lock) {
            boolean sameSize = pixels != null && w == pixelsWidth && h == pixelsHeight;
            if (pixels == null || pixels.capacity() < bytes) {
                pixels = ByteBuffer.allocateDirect(bytes);
            }
            // CEF hands the whole view each time; only the rows its dirty rectangles cover changed.
            int top = 0;
            int bottom = h;
            if (sameSize && dirty != null && dirty.length > 0) {
                top = h;
                bottom = 0;
                for (Rectangle r : dirty) {
                    top = Math.min(top, Math.max(0, r.y));
                    bottom = Math.max(bottom, Math.min(h, r.y + r.height));
                }
            }
            copyRows(buffer, pixels, w, top, bottom);
            pixels.position(0).limit(bytes);
            pixelsWidth = w;
            pixelsHeight = h;
            if (!sameSize || dirtyBottom <= dirtyTop) {
                dirtyTop = sameSize ? top : 0;
                dirtyBottom = sameSize ? bottom : h;
            } else if (bottom > top) {
                dirtyTop = Math.min(dirtyTop, top);
                dirtyBottom = Math.max(dirtyBottom, bottom);
            }
            version++;
        }
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return true;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
    }

    // ---- CefBrowser ----

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    public Component getUIComponent() {
        return SOURCE;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(CefClient client, String url, CefRequestContext context,
            CefBrowser_N parent, Point inspectAt) {
        return null;
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        CompletableFuture<BufferedImage> future = new CompletableFuture<>();
        future.completeExceptionally(new UnsupportedOperationException("not supported off screen"));
        return future;
    }
}
