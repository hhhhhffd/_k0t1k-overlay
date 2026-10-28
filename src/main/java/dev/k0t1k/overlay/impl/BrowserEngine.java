package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;

/**
 * A web browser drawn off screen, implemented by the CEF bridge ({@code src/cef}), which lives in a
 * class loader of its own next to the downloaded JCEF. Only JDK types cross, so the bridge needs
 * nothing else from the overlay. Input uses java.awt.event ids, key codes and extended modifier masks.
 */
public interface BrowserEngine {
    /** The view size in pixels. */
    void resize(int width, int height);

    void load(String url);

    void back();

    void forward();

    void reload();

    String url();

    String title();

    boolean loading();

    void mouse(int id, int x, int y, int modifiers, int clicks, int button);

    /** {@code amount} in lines, positive scrolls down. */
    void wheel(int x, int y, int modifiers, int amount);

    void key(int id, int keyCode, char keyChar, int modifiers);

    void focus(boolean focused);

    /**
     * Copies the newest frame (BGRA, top-down rows) into {@code out} when it is newer than
     * {@code seen}; fills {@code size} with its width and height and returns its version.
     */
    long frame(long seen, ByteBuffer out, int[] size);

    void close();
}
