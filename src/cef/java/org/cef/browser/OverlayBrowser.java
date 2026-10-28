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
    private int width = 800;
    private int height = 600;
    private ByteBuffer pixels;
    private int pixelsWidth;
    private int pixelsHeight;
    private long version;
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
     * Always fills {@code size}; returns -1 when {@code out} is too small for it.
     */
    public long copyFrame(long seen, ByteBuffer out, int[] size) {
        synchronized (lock) {
            if (pixels == null || version == seen) {
                return seen;
            }
            size[0] = pixelsWidth;
            size[1] = pixelsHeight;
            if (out.capacity() < pixels.limit()) {
                return -1;
            }
            pixels.rewind();
            out.clear();
            out.put(pixels);
            out.flip();
            pixels.rewind();
            return version;
        }
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
        sendKeyEvent(new KeyEvent(SOURCE, id, System.currentTimeMillis(), modifiers, keyCode, keyChar));
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
        return new Rectangle(0, 0, width, height);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo info) {
        info.Set(1.0, 32, 8, false, new Rectangle(0, 0, width, height), new Rectangle(0, 0, width, height));
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
        synchronized (lock) {
            int bytes = w * h * 4;
            if (pixels == null || pixels.capacity() < bytes) {
                pixels = ByteBuffer.allocateDirect(bytes);
            }
            pixels.clear();
            ByteBuffer source = buffer.duplicate();
            source.rewind();
            source.limit(Math.min(source.capacity(), bytes));
            pixels.put(source);
            pixels.flip();
            pixelsWidth = w;
            pixelsHeight = h;
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
