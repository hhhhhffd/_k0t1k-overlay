package dev.k0t1k.overlay.cef;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.SystemBootstrap;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.OverlayBrowser;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.network.CefRequest;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import dev.k0t1k.overlay.impl.BrowserEngine;

/**
 * The overlay's browser on top of JCEF: one off-screen Chromium view (a tab). Created by name from the
 * overlay once JCEF and its natives are downloaded; see {@link #create}.
 */
public final class CefEngine implements BrowserEngine {
    private static final String PREFIX = "k0t1k:";
    /**
     * Run in every page after it loads. Off screen CEF cancels popups before Java hears of them, so
     * links meant for a new window and middle or Ctrl clicks are caught here and reported through the
     * console; it also reports whether anything is playing sound.
     */
    private static final String PAGE_SCRIPT = "(function(){if(window.__k0t1k)return;window.__k0t1k=1;"
            + "function say(s){console.log('" + PREFIX + "'+s);}"
            + "window.open=function(u){if(u){try{say('open:'+new URL(u,location.href).href);}catch(e){}}return null;};"
            + "function link(e){var t=e.target;return t&&t.closest?t.closest('a[href]'):null;}"
            + "document.addEventListener('click',function(e){var a=link(e);if(!a||e.defaultPrevented||!/^https?:/.test(a.href))return;"
            + "var t=a.target;if(e.ctrlKey||e.metaKey){e.preventDefault();say('background:'+a.href);}"
            + "else if(t&&t!=='_self'&&t!=='_top'&&t!=='_parent'){e.preventDefault();say('open:'+a.href);}},true);"
            + "document.addEventListener('auxclick',function(e){var a=link(e);"
            + "if(e.button===1&&a&&/^https?:/.test(a.href)){e.preventDefault();say('background:'+a.href);}},true);"
            + "function media(){var n=0;document.querySelectorAll('video,audio').forEach(function(m){"
            + "if(!m.paused&&!m.ended&&!m.muted&&m.volume>0)n++;});say('media:'+n);}"
            + "['play','pause','ended','volumechange'].forEach(function(t){document.addEventListener(t,media,true);});})();";
    /** More than this many unread new-tab requests are dropped, so a page cannot flood the tab bar. */
    private static final int MAX_OPEN_REQUESTS = 8;

    private static CefApp app;

    private final CefClient client;
    private final OverlayBrowser browser;
    private final Queue<String> openRequests = new ConcurrentLinkedQueue<>();
    private final AtomicReference<String[]> contextMenu = new AtomicReference<>();
    private volatile String url = "";
    /** Address of the top-level document, read by the network thread; the first-party cookie URL is only its registrable site. */
    private volatile String pageUrl = "";
    private volatile String title = "";
    private volatile boolean loading;
    private volatile boolean fullscreen;
    private volatile boolean playing;
    private final AtomicInteger blocked = new AtomicInteger();
    private final BiFunction<String, Boolean, String> scripts;
    private boolean scriptFailed;
    /** Guarded by this: the browser exists natively, and a close was asked for. */
    private boolean created;
    private boolean closeRequested;
    private boolean visible = true;

    private CefEngine(CefClient client, String start, Predicate<String[]> blocker, BiFunction<String, Boolean, String> scripts) {
        this.client = client;
        this.url = start;
        this.pageUrl = start;
        this.scripts = scripts;
        if (blocker != null) {
            addBlocker(blocker);
        }
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public void onAfterCreated(CefBrowser b) {
                synchronized (CefEngine.this) {
                    created = true;
                    // Native creation is asynchronous; a close asked for before it was deferred until now.
                    if (closeRequested) {
                        b.close(true);
                        client.dispose();
                    }
                }
            }
        });
        client.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser b, CefFrame frame, String address) {
                if (frame == null || frame.isMain()) {
                    url = address;
                    pageUrl = nonNull(address);
                    inject(frame, false);
                }
            }

            @Override
            public void onTitleChange(CefBrowser b, String newTitle) {
                title = newTitle == null ? "" : newTitle;
            }

            @Override
            public void onFullscreenModeChange(CefBrowser b, boolean on) {
                fullscreen = on;
            }

            @Override
            public boolean onConsoleMessage(CefBrowser b, CefSettings.LogSeverity level, String message, String source, int line) {
                if (message == null || !message.startsWith(PREFIX)) {
                    return false;
                }
                String report = message.substring(PREFIX.length());
                if (report.startsWith("media:")) {
                    playing = !report.equals("media:0");
                } else if (openRequests.size() < MAX_OPEN_REQUESTS) {
                    openRequests.add(report);
                }
                return true;
            }
        });
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser b, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                loading = isLoading;
            }

            @Override
            public void onLoadStart(CefBrowser b, CefFrame frame, CefRequest.TransitionType transitionType) {
                if (frame != null && frame.isMain()) {
                    blocked.set(0);
                    pageUrl = nonNull(frame.getURL());
                    inject(frame, true);
                }
            }

            @Override
            public void onLoadEnd(CefBrowser b, CefFrame frame, int httpStatusCode) {
                inject(frame, false);
                if (frame != null) {
                    frame.executeJavaScript(PAGE_SCRIPT, frame.getURL(), 0);
                }
            }
        });
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
            @Override
            public void onBeforeContextMenu(CefBrowser b, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
                // The native menu cannot show off screen; the overlay draws its own from these.
                boolean image = params.getMediaType() == CefContextMenuParams.MediaType.CM_MEDIATYPE_IMAGE;
                contextMenu.set(new String[] {
                    Integer.toString(params.getXCoord()), Integer.toString(params.getYCoord()),
                    nonNull(params.getLinkUrl()), image ? nonNull(params.getSourceUrl()) : "",
                    nonNull(params.getSelectionText()), params.isEditable() ? "1" : "0",
                    Integer.toString(params.getEditStateFlags()),
                });
                model.clear();
            }
        });
        browser = new OverlayBrowser(client, start, null);
        browser.createImmediately();
    }

    /**
     * Cancels the requests the overlay's filters name. This runs on Chromium's network thread for every
     * request, so it only asks the (lock-free) predicate; a page's own main document is never cancelled.
     */
    private void addBlocker(Predicate<String[]> blocker) {
        CefResourceRequestHandler handler = new CefResourceRequestHandlerAdapter() {
            private boolean failed;

            @Override
            public boolean onBeforeResourceLoad(CefBrowser b, CefFrame frame, CefRequest request) {
                try {
                    CefRequest.ResourceType type = request.getResourceType();
                    if (type == CefRequest.ResourceType.RT_MAIN_FRAME) {
                        return false;
                    }
                    String page = pageUrl;
                    if (page.isEmpty()) {
                        page = request.getFirstPartyForCookies();
                        if (page == null || page.isEmpty()) {
                            page = request.getReferrerURL();
                        }
                    }
                    // Rules' domain= is about the document making the request, which for an iframe is not the top page.
                    String document = frame != null && !frame.isMain() ? nonNull(frame.getURL()) : "";
                    if (document.isEmpty()) {
                        document = nonNull(page);
                    }
                    if (blocker.test(new String[] {request.getURL(), type.name(), nonNull(page), document})) {
                        blocked.incrementAndGet();
                        return true;
                    }
                } catch (RuntimeException e) {
                    if (!failed) {
                        failed = true;
                        System.err.println("[k0t1k-overlay] request filter failed: " + e);
                    }
                }
                return false;
            }
        };
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame frame, CefRequest request,
                    boolean isNavigation, boolean isDownload, String requestInitiator, BoolRef disableDefaultHandling) {
                return handler;
            }
        });
    }

    /** Runs the overlay's protection script in a page; see {@code Protection.scripts}. */
    private void inject(CefFrame frame, boolean full) {
        if (scripts == null || frame == null || !frame.isMain()) {
            return;
        }
        try {
            String js = scripts.apply(frame.getURL(), full);
            if (!js.isEmpty()) {
                frame.executeJavaScript(js, frame.getURL(), 0);
            }
        } catch (RuntimeException e) {
            if (!scriptFailed) {
                scriptFailed = true;
                System.err.println("[k0t1k-overlay] page protection failed: " + e);
            }
        }
    }

    @Override
    public int blocked() {
        return blocked.get();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    /**
     * Starts CEF from the natives in {@code dir} (once per JVM) and opens a browser at {@code start}.
     * {@code cache} keeps cookies and logins between games. Called reflectively by the overlay.
     */
    public static synchronized BrowserEngine create(File dir, File cache, String language, String start,
            Predicate<String[]> blocker, BiFunction<String, Boolean, String> scripts) {
        if (app == null) {
            app = start(dir, cache, language);
        }
        return new CefEngine(app.createClient(), start, blocker, scripts);
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
        // Off screen, JCEF runs CEF with an external message pump on the AWT thread: CEF asks for work
        // through onScheduleMessagePumpWork, and this first call starts the cycle.
        instance.doMessageLoopWork(0);
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
    public void zoom(double level) {
        browser.setZoomLevel(level);
    }

    @Override
    public void visible(boolean show) {
        if (show != visible) {
            visible = show;
            browser.setWindowlessFrameRate(show ? 30 : 1);
        }
    }

    @Override
    public boolean fullscreen() {
        return fullscreen;
    }

    @Override
    public void exitFullscreen() {
        browser.executeJavaScript("document.exitFullscreen&&document.exitFullscreen()", url, 0);
    }

    @Override
    public boolean playing() {
        return playing;
    }

    @Override
    public String pollOpenRequest() {
        return openRequests.poll();
    }

    @Override
    public String[] pollContextMenu() {
        return contextMenu.getAndSet(null);
    }

    @Override
    public void command(int command) {
        CefFrame frame = browser.getFocusedFrame();
        if (frame == null) {
            frame = browser.getMainFrame();
        }
        if (frame == null) {
            return;
        }
        switch (command) {
            case UNDO: frame.undo(); break;
            case REDO: frame.redo(); break;
            case CUT: frame.cut(); break;
            case COPY: frame.copy(); break;
            case PASTE: frame.paste(); break;
            case SELECT_ALL: frame.selectAll(); break;
            default: break;
        }
    }

    @Override
    public void clearCookies() {
        CefCookieManager.getGlobalManager().deleteCookies("", "");
    }

    @Override
    public boolean setCookie(String targetUrl, String name, String value, String domain, String path,
            boolean secure, boolean httpOnly, long expiresMillis) {
        Date now = new Date();
        boolean hasExpires = expiresMillis != 0;
        Date expires = hasExpires ? new Date(expiresMillis) : now;
        CefCookie cookie = new CefCookie(name, value, domain, path, secure, httpOnly, now, now, hasExpires, expires);
        return CefCookieManager.getGlobalManager().setCookie(targetUrl, cookie);
    }

    @Override
    public void flushCookies() {
        CefCookieManager.getGlobalManager().flushStore(null);
    }

    @Override
    public synchronized void close() {
        closeRequested = true;
        if (created) {
            browser.close(true);
            client.dispose();
        }
    }
}
