package dev.k0t1k.overlay.cef;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.SystemBootstrap;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.OverlayBrowser;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import dev.k0t1k.overlay.impl.BrowserEngine;

/**
 * The overlay's browser on top of JCEF: one off-screen Chromium view. Created by name from the
 * overlay once JCEF and its natives are downloaded; see {@link #create}.
 */
public final class CefEngine implements BrowserEngine {
    private static CefApp app;

    private final CefClient client;
    private final OverlayBrowser browser;
    private volatile String url = "";
    private volatile String title = "";
    private volatile boolean loading;

    private CefEngine(CefClient client, String start) {
        this.client = client;
        this.url = start;
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser b, CefFrame frame, String address) {
                if (frame == null || frame.isMain()) {
                    url = address;
                }
            }

            @Override
            public void onTitleChange(CefBrowser b, String newTitle) {
                title = newTitle == null ? "" : newTitle;
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                loading = isLoading;
            }
        });
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser b, CefFrame frame, String target, String name) {
                // No windows to pop up into: open links meant for a new tab in this view instead.
                if (target != null && !target.isEmpty()) {
                    b.loadURL(target);
                }
                return true;
            }
        });
        browser = new OverlayBrowser(client, start, null);
        browser.createImmediately();
    }

    /**
     * Starts CEF from the natives in {@code dir} (once per JVM) and opens a browser at {@code start}.
     * {@code cache} keeps cookies and logins between games. Called reflectively by the overlay.
     */
    public static synchronized BrowserEngine create(File dir, File cache, String language, String start) {
        if (app == null) {
            app = start(dir, cache, language);
        }
        return new CefEngine(app.createClient(), start);
    }

    private static CefApp start(File dir, File cache, String language) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean windows = os.startsWith("windows");
        // The first run would show Chromium's EULA dialog, which cannot open off screen (exit code 28).
        List<String> args = new ArrayList<>(Arrays.asList("--no-first-run", "--disable-gpu"));
        if (!windows) {
            args.add("--no-sandbox");
        }
        String[] argv = args.toArray(new String[0]);
        // Libraries are loaded below by absolute path; JCEF's own loader would search java.library.path.
        SystemBootstrap.setLoader(name -> { });
        if (windows) {
            try {
                System.loadLibrary("jawt");
            } catch (UnsatisfiedLinkError e) {
                System.err.println("[k0t1k-overlay] browser: no jawt (" + e.getMessage() + "), going on");
            }
            System.load(new File(dir, "chrome_elf.dll").getAbsolutePath());
            System.load(new File(dir, "libcef.dll").getAbsolutePath());
            System.load(new File(dir, "jcef.dll").getAbsolutePath());
        } else {
            System.load(new File(dir, "libjcef.so").getAbsolutePath());
            if (!CefApp.startup(argv)) {
                throw new IllegalStateException("CEF did not start");
            }
            System.load(new File(dir, "libcef.so").getAbsolutePath());
        }
        CefSettings settings = new CefSettings();
        settings.windowless_rendering_enabled = true;
        settings.browser_subprocess_path = new File(dir, windows ? "jcef_helper.exe" : "jcef_helper").getAbsolutePath();
        settings.resources_dir_path = dir.getAbsolutePath();
        settings.locales_dir_path = new File(dir, "locales").getAbsolutePath();
        settings.root_cache_path = cache.getAbsolutePath();
        settings.persist_session_cookies = true;
        settings.locale = language;
        settings.log_file = new File(cache, "cef.log").getAbsolutePath();
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;
        CefApp instance = CefApp.getInstance(argv, settings);
        if (!windows) {
            // Linux has no message loop thread of its own; this keeps pumping CEF on the AWT thread.
            instance.doMessageLoopWork(0);
        }
        return instance;
    }

    @Override
    public void resize(int width, int height) {
        browser.resize(width, height);
    }

    @Override
    public void load(String address) {
        browser.loadURL(address);
    }

    @Override
    public void back() {
        browser.goBack();
    }

    @Override
    public void forward() {
        browser.goForward();
    }

    @Override
    public void reload() {
        browser.reload();
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public boolean loading() {
        return loading;
    }

    @Override
    public void mouse(int id, int x, int y, int modifiers, int clicks, int button) {
        browser.mouse(id, x, y, modifiers, clicks, button);
    }

    @Override
    public void wheel(int x, int y, int modifiers, int amount) {
        browser.wheel(x, y, modifiers, amount);
    }

    @Override
    public void key(int id, int keyCode, char keyChar, int modifiers) {
        browser.key(id, keyCode, keyChar, modifiers);
    }

    @Override
    public void focus(boolean focused) {
        browser.setFocus(focused);
    }

    @Override
    public long frame(long seen, ByteBuffer out, int[] size) {
        return browser.copyFrame(seen, out, size);
    }

    @Override
    public void close() {
        browser.close(true);
        client.dispose();
    }
}
