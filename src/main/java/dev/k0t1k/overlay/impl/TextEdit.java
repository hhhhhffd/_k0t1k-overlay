package dev.k0t1k.overlay.impl;

/** Editable text with a caret and a selection ({@link #anchor} to {@link #caret}), in UTF-16 indices. */
final class TextEdit {
    final StringBuilder text = new StringBuilder();
    int caret;
    int anchor;
    /** Scroll offset kept by the field that draws this edit. */
    float scroll;
    boolean caretMoved;
    boolean dragging;

    TextEdit() {
    }

    TextEdit(String initial) {
        text.append(initial);
        caret = anchor = text.length();
    }

    String value() {
        return text.toString();
    }

    void set(String value) {
        text.setLength(0);
        text.append(value);
        caret = anchor = text.length();
        scroll = 0;
        caretMoved = true;
    }

    boolean hasSelection() {
        return caret != anchor;
    }

    String selected() {
        return text.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    void moveTo(int index, boolean select) {
        caret = Math.max(0, Math.min(text.length(), index));
        if (!select) {
            anchor = caret;
        }
        caretMoved = true;
    }

    void selectAll() {
        anchor = 0;
        caret = text.length();
        caretMoved = true;
    }

    void selectWord(int index) {
        anchor = wordStart(index);
        caret = wordEnd(index);
        caretMoved = true;
    }

    /** Replaces the selection (or inserts at the caret). */
    void insert(String value) {
        int start = Math.min(caret, anchor);
        text.replace(start, Math.max(caret, anchor), value);
        caret = anchor = start + value.length();
        caretMoved = true;
    }

    boolean backspace(boolean word) {
        if (hasSelection()) {
            insert("");
            return true;
        }
        if (caret == 0) {
            return false;
        }
        anchor = word ? wordStart(caret) : text.offsetByCodePoints(caret, -1);
        insert("");
        return true;
    }

    boolean delete(boolean word) {
        if (hasSelection()) {
            insert("");
            return true;
        }
        if (caret == text.length()) {
            return false;
        }
        anchor = word ? wordEnd(caret) : text.offsetByCodePoints(caret, 1);
        insert("");
        return true;
    }

    void left(boolean word, boolean select) {
        if (hasSelection() && !select) {
            moveTo(Math.min(caret, anchor), false);
        } else if (caret > 0) {
            moveTo(word ? wordStart(caret) : text.offsetByCodePoints(caret, -1), select);
        }
    }

    void right(boolean word, boolean select) {
        if (hasSelection() && !select) {
            moveTo(Math.max(caret, anchor), false);
        } else if (caret < text.length()) {
            moveTo(word ? wordEnd(caret) : text.offsetByCodePoints(caret, 1), select);
        }
    }

    int lineStart() {
        int i = caret;
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return i;
    }

    int lineEnd() {
        int i = caret;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** Start of the word left of {@code index}, skipping whitespace first. */
    private int wordStart(int index) {
        int i = index;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    /** End of the word right of {@code index}, skipping whitespace first. */
    private int wordEnd(int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }
}
