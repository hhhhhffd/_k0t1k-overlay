package dev.k0t1k.overlay.impl;

import java.nio.ByteBuffer;

/**
 * A web browser drawn off screen, implemented by the CEF bridge ({@code src/cef}), which lives in a
 * class loader of its own next to the downloaded JCEF. Only JDK types cross, so the bridge needs
 * nothing else from the overlay. Input uses java.awt.event ids, key codes and extended modifier masks.
 */
public interface BrowserEngine {
    /** Commands for {@link #command}, run on the focused frame. */
    int UNDO = 0;
    int REDO = 1;
    int CUT = 2;
    int COPY = 3;
    int PASTE = 4;
    int SELECT_ALL = 5;

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

    /** Page zoom: 0 is 100%, each step scales by 1.2. */
    void zoom(double level);

    /** Whether the tab is on screen; hidden ones keep running but draw rarely. */
    void visible(boolean visible);

    /** Whether the page asked for fullscreen (a video): the view should fill the whole area. */
    boolean fullscreen();

    void exitFullscreen();

    /** Whether a media element on the page is playing sound. */
    boolean playing();

    /**
     * The next link the page wants in a new tab, as {@code open:<url>} (to switch to) or
     * {@code background:<url>} (middle or Ctrl click), or null.
     */
    String pollOpenRequest();

    /**
     * The context menu the player asked for, or null: {x, y} in view pixels, link address, image
     * address, selected text, "1" when the field under it is editable, the edit-state bits.
     */
    String[] pollContextMenu();

    void command(int command);

    /** Deletes the cookies of every site (logins included). */
    void clearCookies();

    /**
     * Sets one cookie, as if the site had set it itself. {@code url} must be
     * {@code (secure ? "https://" : "http://") + <domain without a leading dot> + path}. {@code expiresMillis}
     * of 0 makes a session cookie. Returns whether CEF accepted it.
     */
    boolean setCookie(String url, String name, String value, String domain, String path, boolean secure,
            boolean httpOnly, long expiresMillis);

    /** Flushes cookies to disk; called once after a batch of {@link #setCookie} calls. */
    void flushCookies();

    void close();
}
