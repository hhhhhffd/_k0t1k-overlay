package dev.k0t1k.overlay.impl;

/** What the overlay needs from the window library the game uses. */
interface Platform {
    /** Fills {framebuffer width, framebuffer height, window width, window height}. */
    void sizes(int[] out);

    /** The monitor's UI scale as the OS reports it (1 when unknown). */
    float contentScale();

    String clipboard();

    void setClipboard(String text);

    /** Frees the cursor for the overlay, remembering how the game had it. */
    void showCursor();

    /** Gives the cursor back to the game in the mode it last asked for. */
    void restoreCursor();
}
