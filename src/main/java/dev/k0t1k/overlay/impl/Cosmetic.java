package dev.k0t1k.overlay.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cosmetic rules of the filter lists: element hiding ({@code ##selector}, {@code host##selector}, the
 * {@code #@#} exceptions) turned into CSS, and the {@code host##+js(...)} scriptlet calls. Procedural
 * selectors that need uBlock's own engine ({@code :has-text}, {@code :xpath}, ...) are skipped; the
 * browser handles {@code :has()} itself. Immutable once built.
 */
final class Cosmetic {
    /** Generic rules are many thousands; only this many are sent to a page, simple ones first. */
    private static final int GENERIC_CAP = 8000;
    /** A bad selector invalidates its whole CSS rule, so rules stay small. */
    private static final int CHUNK = 20;
    private static final Pattern RULE = Pattern.compile("^([^\\s#/*|^$]*)(#@?#)(.+)$");
    private static final Pattern SIMPLE = Pattern.compile("^[.#][A-Za-z0-9_-]+$");
    private static final String[] UNSUPPORTED = {":has-text(", ":-abp-", ":xpath(", ":upward(", ":matches-", ":min-text",
        ":others(", ":watch-attr", ":remove(", ":style(", ":contains(", ":nth-ancestor(", ":matches-path(", ":remove",
        ":not(:has-text", "{", "}", "<", "\\0"};

    private final String[] generic;
    private final Set<String> genericExceptions;
    private final Map<String, String[]> sites;
    private final Map<String, Set<String>> siteExceptions;
    private final Map<String, List<String[]>> scriptlets;
    /** The CSS of {@link #generic} when no exception applies, built on first use. */
    private volatile String genericCss;

    private Cosmetic(Builder b) {
        List<String> simple = new ArrayList<>();
        List<String> complex = new ArrayList<>();
        for (String s : b.generic) {
            if (!b.genericExceptions.contains(s)) {
                (SIMPLE.matcher(s).matches() ? simple : complex).add(s);
            }
        }
        simple.addAll(complex);
        generic = simple.subList(0, Math.min(simple.size(), GENERIC_CAP)).toArray(new String[0]);
        genericExceptions = b.genericExceptions;
        sites = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : b.sites.entrySet()) {
            sites.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        siteExceptions = b.siteExceptions;
        scriptlets = b.scriptlets;
    }

    static final class Builder {
        private final Set<String> generic = new LinkedHashSet<>();
        private final Set<String> genericExceptions = new HashSet<>();
        private final Map<String, Set<String>> sites = new HashMap<>();
        private final Map<String, Set<String>> siteExceptions = new HashMap<>();
        private final Map<String, List<String[]>> scriptlets = new HashMap<>();
        int rules;
        int scripts;
        int skipped;

        /** Adds a line if it is a cosmetic rule; returns whether it was one (skipped or not). */
        boolean add(String raw) {
            String line = raw.trim();
            Matcher m = RULE.matcher(line);
            if (!m.matches()) {
                return false;
            }
            boolean exception = m.group(2).equals("#@#");
            String body = m.group(3);
            List<String> hosts = new ArrayList<>();
            if (!m.group(1).isEmpty()) {
                for (String h : m.group(1).toLowerCase(java.util.Locale.ROOT).split(",")) {
                    if (h.startsWith("~") || h.indexOf('*') >= 0) {
                        // Negated and entity domains would need more than a host table.
                        if (!h.startsWith("~")) {
                            skipped++;
                            return true;
                        }
                    } else if (!h.isEmpty()) {
                        hosts.add(h);
                    }
                }
                if (hosts.isEmpty()) {
                    skipped++;
                    return true;
                }
            }
            if (body.startsWith("+js(") && body.endsWith(")")) {
                if (exception || hosts.isEmpty()) {
                    skipped++;
                    return true;
                }
                String[] call = scriptletCall(body.substring(4, body.length() - 1));
                for (String h : hosts) {
                    scriptlets.computeIfAbsent(h, k -> new ArrayList<>(1)).add(call);
                }
                scripts++;
                return true;
            }
            if (!selectorOk(body)) {
                skipped++;
                return true;
            }
            if (exception) {
                if (hosts.isEmpty()) {
                    genericExceptions.add(body);
                } else {
                    for (String h : hosts) {
                        siteExceptions.computeIfAbsent(h, k -> new HashSet<>()).add(body);
                    }
                }
            } else if (hosts.isEmpty()) {
                generic.add(body);
            } else {
                for (String h : hosts) {
                    sites.computeIfAbsent(h, k -> new LinkedHashSet<>()).add(body);
                }
            }
            rules++;
            return true;
        }

        Cosmetic build() {
            return new Cosmetic(this);
        }
    }

    private static String[] scriptletCall(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && i + 1 < args.length() && args.charAt(i + 1) == ',') {
                current.append(',');
                i++;
            } else if (c == ',') {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString().trim());
        return parts.toArray(new String[0]);
    }

    private static boolean selectorOk(String s) {
        if (s.isEmpty() || s.charAt(0) == '^') {
            return false;
        }
        for (String bad : UNSUPPORTED) {
            if (s.contains(bad)) {
                return false;
            }
        }
        int square = 0;
        int round = 0;
        int quotes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '[') {
                square++;
            } else if (c == ']') {
                square--;
            } else if (c == '(') {
                round++;
            } else if (c == ')') {
                round--;
            } else if (c == '"' || c == '\'') {
                quotes++;
            }
            if (square < 0 || round < 0) {
                return false;
            }
        }
        return square == 0 && round == 0 && quotes % 2 == 0;
    }

    /** Calls to run on a page of {@code host}: {name, arguments...}. */
    List<String[]> scriptlets(String host) {
        List<String[]> out = new ArrayList<>();
        for (String h = host; ; ) {
            List<String[]> calls = scriptlets.get(h);
            if (calls != null) {
                out.addAll(calls);
            }
            int dot = h.indexOf('.');
            if (dot < 0) {
                return out;
            }
            h = h.substring(dot + 1);
        }
    }

    /** The CSS that hides the page's elements, or "" when no rule applies. */
    String css(String host, boolean withGeneric) {
        List<String> selectors = new ArrayList<>();
        Set<String> except = null;
        for (String h = host; ; ) {
            String[] own = sites.get(h);
            if (own != null) {
                selectors.addAll(java.util.Arrays.asList(own));
            }
            Set<String> ex = siteExceptions.get(h);
            if (ex != null) {
                if (except == null) {
                    except = new HashSet<>();
                }
                except.addAll(ex);
            }
            int dot = h.indexOf('.');
            if (dot < 0) {
                break;
            }
            h = h.substring(dot + 1);
        }
        StringBuilder css = new StringBuilder();
        if (withGeneric) {
            if (except == null) {
                String cached = genericCss;
                if (cached == null) {
                    cached = genericCss = chunks(java.util.Arrays.asList(generic), null);
                }
                css.append(cached);
            } else {
                css.append(chunks(java.util.Arrays.asList(generic), except));
            }
        }
        css.append(chunks(selectors, except));
        return css.toString();
    }

    private static String chunks(List<String> selectors, Set<String> except) {
        StringBuilder css = new StringBuilder();
        int inChunk = 0;
        for (String s : selectors) {
            if (except != null && except.contains(s)) {
                continue;
            }
            css.append(inChunk == 0 ? "" : ",").append(s);
            if (++inChunk == CHUNK) {
                css.append("{display:none!important}\n");
                inChunk = 0;
            }
        }
        if (inChunk > 0) {
            css.append("{display:none!important}\n");
        }
        return css.toString();
    }
}
