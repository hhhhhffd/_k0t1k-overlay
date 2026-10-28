package dev.k0t1k.overlay.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Opens web pages and folders with the operating system's handler. java.awt.Desktop is avoided: it
 * starts AWT inside the game, which fights GLFW for the main thread on macOS. Each target is passed
 * as one process argument, never through a shell.
 */
final class Links {
    private Links() {
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
        builder.start();
    }
}
