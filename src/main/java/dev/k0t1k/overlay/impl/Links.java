package dev.k0t1k.overlay.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Opens web pages and folders with the operating system's handler. java.awt.Desktop is avoided: it
 * starts AWT inside the game, which fights GLFW for the main thread on macOS. Each target is passed
 * as one process argument, never through a shell.
 */
final class Links {
    /** Set on a thread while the overlay itself starts a handler, so the link hook leaves that launch alone. */
    static final ThreadLocal<Boolean> OWN = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Links() {
    }

    /** The address in a command that only opens a web page in the system's browser, or null. */
    static String openedAddress(List<String> command) {
        if (command.size() == 3 && baseName(command.get(0)).matches("rundll32(\\.exe)?")
                && command.get(1).equalsIgnoreCase("url.dll,FileProtocolHandler")) {
            return webAddress(command.get(2));
        }
        if (command.size() == 2 && (baseName(command.get(0)).equals("open") || baseName(command.get(0)).equals("xdg-open"))) {
            return webAddress(command.get(1));
        }
        return null;
    }

    private static String baseName(String program) {
        return program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
    }

    /** {@code text} when it is an http(s) address, else null. */
    static String webAddress(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") ? t : null;
    }

    /** SDL_OpenURL's argument as text: a CharSequence or a NUL-ended UTF-8 buffer. */
    static String text(Object argument) {
        if (argument instanceof CharSequence) {
            return argument.toString();
        }
        if (argument instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer bytes = ((java.nio.ByteBuffer) argument).duplicate();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            while (bytes.hasRemaining()) {
                byte b = bytes.get();
                if (b == 0) {
                    break;
                }
                out.write(b);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** A command that does nothing and exits at once, so a launch that was taken over still returns a process. */
    static List<String> harmless() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return windows ? Arrays.asList("cmd", "/c", "exit", "0") : Collections.singletonList("true");
    }

    /** Opens an http(s) address in the default browser. */
    static void browse(String url) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IOException("not a web address: " + url);
        }
        open(url);
    }

    /** Opens a folder in the file manager. */
    static void folder(Path dir) throws IOException {
        open(dir.toAbsolutePath().toString());
    }

    private static void open(String target) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        ProcessBuilder builder;
        if (windows) {
            builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", target);
        } else if (os.startsWith("mac")) {
            builder = new ProcessBuilder("open", target);
        } else {
            builder = new ProcessBuilder("xdg-open", target);
        }
        // Nobody reads the handler's output; a full pipe would block it.
        builder.redirectErrorStream(true).redirectOutput(new File(windows ? "NUL" : "/dev/null"));
        OWN.set(Boolean.TRUE);
        try {
            builder.start();
        } finally {
            OWN.set(Boolean.FALSE);
        }
    }
}
