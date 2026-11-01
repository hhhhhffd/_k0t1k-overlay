package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** Filter list rules, and the web addresses the game's launches are recognised by. */
class FilterTest {
    private static NetFilter net(String... rules) {
        NetFilter.Builder b = new NetFilter.Builder();
        for (String rule : rules) {
            b.add(rule);
        }
        return b.build();
    }

    @Test
    void hostRulesMatchSubdomainsAndPaths() {
        NetFilter f = net("||ads.example.com^", "||tracker.net/pixel^");
        assertTrue(f.blocks("https://ads.example.com/x.js", NetFilter.SCRIPT, "news.org"));
        assertTrue(f.blocks("https://a.ads.example.com/x.js", NetFilter.SCRIPT, "news.org"));
        assertFalse(f.blocks("https://example.com/x.js", NetFilter.SCRIPT, "news.org"));
        assertFalse(f.blocks("https://badads.example.com/x.js", NetFilter.SCRIPT, "news.org"));
        assertTrue(f.blocks("https://tracker.net/pixel?id=1", NetFilter.IMAGE, "news.org"));
        assertFalse(f.blocks("https://tracker.net/other", NetFilter.IMAGE, "news.org"));
    }

    @Test
    void optionsLimitRules() {
        NetFilter f = net("||cdn.example.com^$third-party", "/banner/*/img^$image", "||only.example^$domain=a.com|~b.a.com");
        assertTrue(f.blocks("https://cdn.example.com/a.js", NetFilter.SCRIPT, "news.org"));
        assertFalse(f.blocks("https://cdn.example.com/a.js", NetFilter.SCRIPT, "example.com"));
        assertTrue(f.blocks("https://site.org/banner/300/img/p.png", NetFilter.IMAGE, "news.org"));
        assertFalse(f.blocks("https://site.org/banner/300/img/p.png", NetFilter.SCRIPT, "news.org"));
        assertTrue(f.blocks("https://only.example/x", NetFilter.XHR, "a.com"));
        assertFalse(f.blocks("https://only.example/x", NetFilter.XHR, "b.a.com"));
        assertFalse(f.blocks("https://only.example/x", NetFilter.XHR, "other.com"));
    }

    @Test
    void exceptionsAndSiteSwitches() {
        NetFilter f = net("||ads.example.com^", "@@||ads.example.com/ok.js", "@@||shop.com^$document");
        assertTrue(f.blocks("https://ads.example.com/bad.js", NetFilter.SCRIPT, "news.org"));
        assertFalse(f.blocks("https://ads.example.com/ok.js", NetFilter.SCRIPT, "news.org"));
        assertTrue(f.documentOff("www.shop.com"));
        assertFalse(f.documentOff("news.org"));
    }

    @Test
    void wordAndWildcardRules() {
        NetFilter f = net("/adserver.", "-banner-ad-", "|https://x.com/a*b|");
        assertTrue(f.blocks("https://site.org/adserver.php", NetFilter.OTHER, ""));
        assertTrue(f.blocks("https://site.org/x-banner-ad-1.png", NetFilter.IMAGE, ""));
        assertTrue(f.blocks("https://x.com/a123b", NetFilter.OTHER, ""));
        assertFalse(f.blocks("https://x.com/a123bc", NetFilter.OTHER, ""));
    }

    @Test
    void unsupportedRulesAreSkipped() {
        NetFilter.Builder b = new NetFilter.Builder();
        b.add("/ads\\d+/");
        b.add("||x.com^$redirect=noop.js");
        b.add("||y.com^$removeparam=a");
        b.add("! comment");
        assertEquals(0, b.rules);
        assertEquals(3, b.skipped);
    }

    @Test
    void cosmeticRulesBecomeCss() {
        Cosmetic.Builder b = new Cosmetic.Builder();
        assertTrue(b.add("##.ad-box"));
        assertTrue(b.add("news.org##div:has(> .sponsored)"));
        assertTrue(b.add("news.org##.x:has-text(foo)"));
        assertTrue(b.add("news.org#@#.ad-box"));
        assertTrue(b.add("youtube.com##+js(json-prune, a.b c)"));
        assertFalse(b.add("||ads.com^"));
        Cosmetic c = b.build();
        assertEquals(3, b.rules);
        assertTrue(c.css("other.org", true).contains(".ad-box"));
        String news = c.css("www.news.org", true);
        assertFalse(news.contains(".ad-box"));
        assertTrue(news.contains("div:has(> .sponsored)"));
        assertEquals(Arrays.asList("json-prune", "a.b c"), Arrays.asList(c.scriptlets("www.youtube.com").get(0)));
    }

    @Test
    void onlyWebAddressesAreTakenFromLaunchCommands() {
        assertEquals("https://example.com/a", Links.openedAddress(Arrays.asList("xdg-open", "https://example.com/a")));
        assertEquals("http://example.com", Links.openedAddress(Arrays.asList("/usr/bin/open", "http://example.com")));
        assertEquals("https://e.com", Links.openedAddress(Arrays.asList("rundll32", "url.dll,FileProtocolHandler", "https://e.com")));
        assertNull(Links.openedAddress(Arrays.asList("xdg-open", "file:///tmp/x")));
        assertNull(Links.openedAddress(Arrays.asList("xdg-open", "/home/me/folder")));
        assertNull(Links.openedAddress(Arrays.asList("ffmpeg", "-i", "http://x")));
        assertNull(Links.openedAddress(Arrays.asList("xdg-open", "https://e.com", "extra")));
    }
}
