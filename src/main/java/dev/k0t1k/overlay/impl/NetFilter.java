package dev.k0t1k.overlay.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Network rules in the Adblock syntax of uBlock Origin's and EasyList's filter lists, matched against
 * request URLs. Immutable once built, so the io thread of Chromium can use it without locks while the
 * overlay swaps in a newer one. Rules are indexed by the host they start with ({@code ||host^...}) or
 * by their longest whole word, so a lookup only tries the few rules that can match.
 *
 * <p>Supported: {@code ||} {@code |} anchors, {@code *} and {@code ^}, the options {@code third-party},
 * {@code 1p}, resource types, {@code domain=}, {@code important}, {@code all} and the {@code @@}
 * exceptions (with {@code $document}, {@code $elemhide} and {@code $generichide} for whole sites).
 * Regular expressions and everything that rewrites a request (redirect, removeparam, csp...) are skipped.
 */
final class NetFilter {
    static final int SCRIPT = 1;
    static final int IMAGE = 2;
    static final int STYLE = 4;
    static final int XHR = 8;
    static final int SUBDOC = 16;
    static final int MEDIA = 32;
    static final int FONT = 64;
    static final int OTHER = 128;
    private static final int ALL = 255;

    private static final int START = 1;
    private static final int HOST = 2;
    private static final int END = 4;
    private static final int IMPORTANT = 8;
    private static final int THIRD = 16;
    private static final int FIRST = 32;

    private static final String[] TWO_PART = {"co", "com", "org", "net", "gov", "edu", "ac", "or", "ne", "go"};

    private static final class Rule {
        final String pattern;
        final int flags;
        final int types;
        /** Page hosts the rule is limited to, or null. */
        final String[] only;
        /** Page hosts it does not apply to, or null. */
        final String[] except;

        Rule(String pattern, int flags, int types, String[] only, String[] except) {
            this.pattern = pattern;
            this.flags = flags;
            this.types = types;
            this.only = only;
            this.except = except;
        }
    }

    private static final class Index {
        final Map<String, Rule[]> byHost;
        final Map<String, Rule[]> byWord;
        final Rule[] rest;

        Index(Map<String, List<Rule>> hosts, Map<String, List<Rule>> words, List<Rule> rest) {
            this.byHost = freeze(hosts);
            this.byWord = freeze(words);
            this.rest = rest.toArray(new Rule[0]);
        }

        private static Map<String, Rule[]> freeze(Map<String, List<Rule>> source) {
            Map<String, Rule[]> out = new HashMap<>(source.size() * 2);
            for (Map.Entry<String, List<Rule>> e : source.entrySet()) {
                out.put(e.getKey(), e.getValue().toArray(new Rule[0]));
            }
            return out;
        }
    }

    private final Index block;
    private final Index allow;
    private final Set<String> documentOff;
    private final Set<String> hideOff;
    private final Set<String> genericOff;

    private NetFilter(Builder b) {
        block = new Index(b.blockHosts, b.blockWords, b.blockRest);
        allow = new Index(b.allowHosts, b.allowWords, b.allowRest);
        documentOff = b.documentOff;
        hideOff = b.hideOff;
        genericOff = b.genericOff;
    }

    static final class Builder {
        private final Map<String, List<Rule>> blockHosts = new HashMap<>();
        private final Map<String, List<Rule>> blockWords = new HashMap<>();
        private final List<Rule> blockRest = new ArrayList<>();
        private final Map<String, List<Rule>> allowHosts = new HashMap<>();
        private final Map<String, List<Rule>> allowWords = new HashMap<>();
        private final List<Rule> allowRest = new ArrayList<>();
        private final Set<String> documentOff = new HashSet<>();
        private final Set<String> hideOff = new HashSet<>();
        private final Set<String> genericOff = new HashSet<>();
        int rules;
        int skipped;

        /** Adds one list line that is not a cosmetic rule. */
        void add(String raw) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') {
                return;
            }
            if (!parse(line)) {
                skipped++;
            }
        }

        private boolean parse(String line) {
            boolean exception = line.startsWith("@@");
            if (exception) {
                line = line.substring(2);
            }
            String options = "";
            int dollar = line.lastIndexOf('$');
            if (dollar >= 0 && line.indexOf('/', dollar) < 0) {
                options = line.substring(dollar + 1);
                line = line.substring(0, dollar);
            }
            if (isRegex(line)) {
                return false;
            }
            int flags = 0;
            int include = 0;
            int exclude = 0;
            String[] only = null;
            String[] except = null;
            boolean document = false;
            boolean hide = false;
            boolean generic = false;
            if (!options.isEmpty()) {
                for (String option : options.split(",")) {
                    String o = option.trim().toLowerCase(Locale.ROOT);
                    boolean negated = o.startsWith("~");
                    String name = negated ? o.substring(1) : o;
                    int type = typeOf(name);
                    if (type != 0) {
                        if (negated) {
                            exclude |= type;
                        } else {
                            include |= type;
                        }
                    } else if (name.equals("third-party") || name.equals("3p")) {
                        flags |= negated ? FIRST : THIRD;
                    } else if (name.equals("first-party") || name.equals("1p")) {
                        flags |= negated ? THIRD : FIRST;
                    } else if (name.equals("important")) {
                        flags |= IMPORTANT;
                    } else if (name.equals("all")) {
                        continue;
                    } else if (name.equals("document") || name.equals("doc")) {
                        document = true;
                    } else if (name.equals("elemhide") || name.equals("ehide")) {
                        hide = true;
                    } else if (name.equals("generichide") || name.equals("ghide")) {
                        generic = true;
                    } else if (o.startsWith("domain=")) {
                        List<String> in = new ArrayList<>();
                        List<String> out = new ArrayList<>();
                        for (String d : o.substring(7).split("\\|")) {
                            if (d.startsWith("~")) {
                                out.add(d.substring(1));
                            } else if (!d.isEmpty() && d.indexOf('*') < 0) {
                                in.add(d);
                            } else if (!d.isEmpty()) {
                                return false;
                            }
                        }
                        only = in.isEmpty() ? null : in.toArray(new String[0]);
                        except = out.isEmpty() ? null : out.toArray(new String[0]);
                    } else if (!name.equals("match-case")) {
                        return false;
                    }
                }
            }
            int types = include != 0 ? include & ~exclude : ALL & ~exclude;
            if (types == 0) {
                return false;
            }
            boolean hostAnchor = line.startsWith("||");
            if (hostAnchor) {
                line = line.substring(2);
                flags |= HOST;
            } else if (line.startsWith("|")) {
                line = line.substring(1);
                flags |= START;
            }
            if (line.endsWith("|")) {
                line = line.substring(0, line.length() - 1);
                flags |= END;
            }
            String pattern = line.toLowerCase(Locale.ROOT);
            if ((flags & END) == 0) {
                while (pattern.endsWith("*")) {
                    pattern = pattern.substring(0, pattern.length() - 1);
                }
            }
            if ((flags & (HOST | START)) == 0) {
                while (pattern.startsWith("*")) {
                    pattern = pattern.substring(1);
                }
            }
            if (pattern.isEmpty()) {
                return false;
            }
            if (exception && (document || hide || generic)) {
                int e = hostEnd(pattern);
                if (!hostAnchor || e == 0 || (e < pattern.length() && pattern.charAt(e) != '^')) {
                    return false;
                }
                String host = pattern.substring(0, e);
                if (document) {
                    documentOff.add(host);
                }
                if (hide || document) {
                    hideOff.add(host);
                }
                if (generic) {
                    genericOff.add(host);
                }
                rules++;
                return true;
            }
            if (document || hide || generic) {
                return false;
            }
            Rule rule = new Rule(pattern, flags, types, only, except);
            Map<String, List<Rule>> hosts = exception ? allowHosts : blockHosts;
            Map<String, List<Rule>> words = exception ? allowWords : blockWords;
            List<Rule> rest = exception ? allowRest : blockRest;
            int e = hostEnd(pattern);
            if (hostAnchor && e > 0 && (e == pattern.length() || "^/:?".indexOf(pattern.charAt(e)) >= 0)) {
                hosts.computeIfAbsent(pattern.substring(0, e), k -> new ArrayList<>(1)).add(rule);
            } else {
                String word = word(pattern, flags);
                if (word == null) {
                    rest.add(rule);
                } else {
                    words.computeIfAbsent(word, k -> new ArrayList<>(1)).add(rule);
                }
            }
            rules++;
            return true;
        }

        NetFilter build() {
            return new NetFilter(this);
        }
    }

    private static boolean isRegex(String line) {
        if (line.length() < 3 || line.charAt(0) != '/' || line.charAt(line.length() - 1) != '/') {
            return false;
        }
        for (int i = 1; i < line.length() - 1; i++) {
            if ("\\([{+?|".indexOf(line.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int typeOf(String name) {
        switch (name) {
            case "script": return SCRIPT;
            case "image": return IMAGE;
            case "stylesheet": case "css": return STYLE;
            case "xmlhttprequest": case "xhr": return XHR;
            case "subdocument": case "frame": return SUBDOC;
            case "media": return MEDIA;
            case "font": return FONT;
            case "other": case "object": return OTHER;
            default: return 0;
        }
    }

    /** Maps a CEF resource type name to a type bit. */
    static int typeOfRequest(String cefType) {
        switch (cefType) {
            case "RT_SCRIPT": case "RT_WORKER": case "RT_SHARED_WORKER": case "RT_SERVICE_WORKER": return SCRIPT;
            case "RT_IMAGE": case "RT_FAVICON": return IMAGE;
            case "RT_STYLESHEET": return STYLE;
            case "RT_XHR": case "RT_PING": case "RT_PREFETCH": return XHR;
            case "RT_SUB_FRAME": return SUBDOC;
            case "RT_MEDIA": return MEDIA;
            case "RT_FONT_RESOURCE": return FONT;
            default: return OTHER;
        }
    }

    private static boolean hostChar(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_';
    }

    private static int hostEnd(String pattern) {
        int e = 0;
        while (e < pattern.length() && hostChar(pattern.charAt(e))) {
            e++;
        }
        return e;
    }

    private static boolean wordChar(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }

    /**
     * The longest run of letters and digits that sits between separators (or anchors) on both sides,
     * so it must also be a whole word of any URL the rule matches.
     */
    private static String word(String p, int flags) {
        String best = null;
        int i = 0;
        while (i < p.length()) {
            if (!wordChar(p.charAt(i))) {
                i++;
                continue;
            }
            int s = i;
            while (i < p.length() && wordChar(p.charAt(i))) {
                i++;
            }
            boolean left = s == 0 ? (flags & (START | HOST)) != 0 : p.charAt(s - 1) != '*';
            boolean right = i == p.length() ? (flags & END) != 0 : p.charAt(i) != '*';
            if (left && right && i - s >= 3 && (best == null || i - s > best.length())) {
                best = p.substring(s, i);
            }
        }
        return best;
    }

    /** The host of an http(s) URL, lower case, or "" for anything else. */
    static String host(String url) {
        int s = url.indexOf("://");
        if (s < 0) {
            return "";
        }
        s += 3;
        int e = s;
        while (e < url.length() && "/?#".indexOf(url.charAt(e)) < 0) {
            e++;
        }
        String authority = url.substring(s, e);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) {
            authority = authority.substring(0, colon);
        }
        return authority.toLowerCase(Locale.ROOT);
    }

    /** A rough registrable domain: the last two labels, or three under country second levels like co.uk. */
    static String site(String host) {
        int last = host.lastIndexOf('.');
        if (last < 0) {
            return host;
        }
        int second = host.lastIndexOf('.', last - 1);
        if (second < 0) {
            return host;
        }
        String label = host.substring(second + 1, last);
        boolean three = host.length() - last == 3 && java.util.Arrays.asList(TWO_PART).contains(label);
        if (three) {
            int third = host.lastIndexOf('.', second - 1);
            return third < 0 ? host : host.substring(third + 1);
        }
        return host.substring(second + 1);
    }

    private static boolean inDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** Whether the page's host is one of the hosts (or under one) of a rule's domain list. */
    private static boolean listed(String[] domains, String pageHost) {
        for (String d : domains) {
            if (inDomain(pageHost, d)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hostIn(Set<String> hosts, String host) {
        for (String h = host; ; ) {
            if (hosts.contains(h)) {
                return true;
            }
            int dot = h.indexOf('.');
            if (dot < 0) {
                return false;
            }
            h = h.substring(dot + 1);
        }
    }

    /** Whether the site has switched network filtering off with an {@code @@...$document} rule. */
    boolean documentOff(String pageHost) {
        return !documentOff.isEmpty() && hostIn(documentOff, pageHost);
    }

    /** Whether cosmetic filtering is off for the site ({@code $elemhide}). */
    boolean hideOff(String pageHost) {
        return !hideOff.isEmpty() && hostIn(hideOff, pageHost);
    }

    /** Whether generic cosmetic filtering is off for the site ({@code $generichide}). */
    boolean genericOff(String pageHost) {
        return !genericOff.isEmpty() && hostIn(genericOff, pageHost);
    }

    /**
     * Whether a request should be cancelled.
     *
     * @param type one of the type bits, from {@link #typeOfRequest}
     * @param pageHost the host of the page it belongs to, "" when unknown
     */
    boolean blocks(String requestUrl, int type, String pageHost) {
        String url = requestUrl.toLowerCase(Locale.ROOT);
        String host = host(url);
        if (host.isEmpty()) {
            return false;
        }
        boolean third = !pageHost.isEmpty() && !site(host).equals(site(pageHost));
        Rule hit = find(block, url, host, type, third, pageHost);
        if (hit == null) {
            return false;
        }
        if ((hit.flags & IMPORTANT) != 0) {
            return true;
        }
        return find(allow, url, host, type, third, pageHost) == null;
    }

    private Rule find(Index index, String url, String host, int type, boolean third, String pageHost) {
        int authority = url.indexOf("://") + 3;
        int hostStart = url.indexOf(host, authority);
        for (int s = 0; ; ) {
            Rule[] rules = index.byHost.get(host.substring(s));
            if (rules != null) {
                for (Rule r : rules) {
                    if (applies(r, type, third, pageHost) && matchAt(r.pattern, 0, url, hostStart + s, (r.flags & END) != 0)) {
                        return r;
                    }
                }
            }
            int dot = host.indexOf('.', s);
            if (dot < 0) {
                break;
            }
            s = dot + 1;
        }
        int i = 0;
        while (i < url.length()) {
            if (!wordChar(url.charAt(i))) {
                i++;
                continue;
            }
            int s = i;
            while (i < url.length() && wordChar(url.charAt(i))) {
                i++;
            }
            Rule[] rules = index.byWord.get(url.substring(s, i));
            if (rules != null) {
                for (Rule r : rules) {
                    if (applies(r, type, third, pageHost) && search(r, url, authority)) {
                        return r;
                    }
                }
            }
        }
        for (Rule r : index.rest) {
            if (applies(r, type, third, pageHost) && search(r, url, authority)) {
                return r;
            }
        }
        return null;
    }

    private static boolean applies(Rule r, int type, boolean third, String pageHost) {
        if ((r.types & type) == 0) {
            return false;
        }
        if ((r.flags & THIRD) != 0 && !third || (r.flags & FIRST) != 0 && third) {
            return false;
        }
        if (r.only != null && (pageHost.isEmpty() || !listed(r.only, pageHost))) {
            return false;
        }
        return r.except == null || pageHost.isEmpty() || !listed(r.except, pageHost);
    }

    private static boolean search(Rule r, String url, int authority) {
        boolean end = (r.flags & END) != 0;
        if ((r.flags & START) != 0) {
            return matchAt(r.pattern, 0, url, 0, end);
        }
        int from = (r.flags & HOST) != 0 ? authority : 0;
        for (int p = from; p <= url.length(); p++) {
            if ((r.flags & HOST) != 0 && p > authority && url.charAt(p - 1) != '.' ) {
                if (url.charAt(p - 1) == '/') {
                    return false;
                }
                continue;
            }
            if (matchAt(r.pattern, 0, url, p, end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean separator(char c) {
        return !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '%');
    }

    private static boolean matchAt(String p, int pi, String u, int ui, boolean end) {
        while (pi < p.length()) {
            char c = p.charAt(pi);
            if (c == '*') {
                while (pi < p.length() && p.charAt(pi) == '*') {
                    pi++;
                }
                if (pi == p.length()) {
                    return true;
                }
                for (int k = ui; k <= u.length(); k++) {
                    if (matchAt(p, pi, u, k, end)) {
                        return true;
                    }
                }
                return false;
            }
            if (c == '^') {
                if (ui == u.length()) {
                    pi++;
                    continue;
                }
                if (!separator(u.charAt(ui))) {
                    return false;
                }
            } else if (ui >= u.length() || u.charAt(ui) != c) {
                return false;
            }
            ui++;
            pi++;
        }
        return !end || ui == u.length();
    }
}
