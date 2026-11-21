package com.example.configdiscovery;

import java.net.IDN;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Synchronous, bounded discovery core. Call scan() on a background executor.
 * Each instance supports one scan at a time. cancel() may be called from another thread.
 * HTTP is supplied by the host app; this class itself never opens a connection.
 */
public final class ConfigDiscovery {
    public interface Fetcher {
        /**
         * GET only; return decompressed body, HTTP status and final redirect URL.
         * Enforce both deadlineNanos (System.nanoTime domain) and timeoutMs;
         * reject, never truncate, bodies exceeding maxBytes. Limit redirects and
         * validate EVERY destination/DNS result using the host's network policy.
         * Do not attach unrelated session cookies, credentials or authorization.
         */
        Response fetch(String url, int timeoutMs, int maxBytes, long deadlineNanos) throws Exception;
    }

    public static final class Response {
        public final byte[] body;
        public final String contentType, finalUrl;
        public final int status;
        public final long totalMs;
        public Response(byte[] body, String contentType, String finalUrl, int status, long totalMs) {
            this.body = body; this.contentType = contentType; this.finalUrl = finalUrl;
            this.status = status; this.totalMs = totalMs;
        }
    }

    public static final class Seed {
        public final String name, url;
        public Seed(String name, String url) { this.name = name == null ? "" : name; this.url = url; }
    }

    public static final class Options {
        public int maxDepth = 3, maxChildren = 100, maxNodes = 700, maxPages = 100;
        public long deadlineMs = 240_000;
    }

    public static final class Node {
        public String name = "", url = "", finalUrl = "", kind = ConfigDecoder.UNKNOWN;
        public boolean configAvailable;
        // Unknown is distinct from tested-and-failed. This scanner does not run providers.
        public Boolean searchVerified = null, playVerified = null;
        public String error = "", note = "", speedLabel = "未测";
        public long totalMs = -1;
        public int siteCount, depth;
        public final List<Node> children = new ArrayList<>();
        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("name", name); obj.put("url", url); obj.put("final_url", finalUrl);
            obj.put("kind", kind); obj.put("config_available", configAvailable);
            obj.put("search_verified", searchVerified == null ? JSONObject.NULL : searchVerified);
            obj.put("play_verified", playVerified == null ? JSONObject.NULL : playVerified);
            obj.put("error", error); obj.put("note", note); obj.put("total_ms", totalMs);
            obj.put("speed_label", speedLabel); obj.put("site_count", siteCount);
            JSONArray items = new JSONArray();
            for (Node child : children) items.put(child.toJson());
            obj.put("children", items);
            return obj;
        }
    }

    public static final class Result {
        public final List<Node> roots;
        public final Map<String, String> pageErrors;
        public final JSONObject config;
        public final boolean cancelledOrTimedOut;
        Result(List<Node> roots, Map<String, String> errors, JSONObject config, boolean stopped) {
            this.roots = Collections.unmodifiableList(roots);
            this.pageErrors = Collections.unmodifiableMap(errors);
            this.config = config; this.cancelledOrTimedOut = stopped;
        }
    }

    private static final Pattern URL_RE = Pattern.compile("https?://[^\\s\"'<>()\\[\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SKIP_SUFFIX = Pattern.compile("\\.(png|jpe?g|gif|webp|svg|ico|css|js|woff2?|ttf|eot|mp4|mp3|zip)$", Pattern.CASE_INSENSITIVE);
    private static final String[] SKIP = {"github.com/", "gitlab.com/", "gitee.com/", "zhihu.com", "bilibili", "baidu.com", "google.com", "bing.com", "youtube", "douyin", "weibo", "taobao", "jd.com", "xiaomi.com", "360.cn", "sohu", "163.com", "sina.com", "cloudflare", "jsdelivr.net", "unpkg.com", "gravatar", "w3.org", "mozilla.org", "jquery", "bootstrap", "miit.gov.cn", "flowus.cn", "allflow.cn", "staticfile.org"};
    private static final String[] LINE = {"/tv", "/box", "/api", ".json", ".txt", "/dc", "kstore", "jundie", "catvod", "tvbox", "yingm", "liucn", "nxog", "tspider", "/dm/", "dcang", "仓库", "线路", "接口", "配置"};
    private static final String[] PROXY = {"gh-proxy.com", "ghproxy.net", "ghproxy.com", "ghfast.top", "mirror.ghproxy.com", "gh.llkk.cc"};
    private final Fetcher fetcher;
    private final int maxDepth, maxChildren, maxNodes, maxPages;
    private final long deadlineMs;
    private volatile boolean cancelled;
    private long deadlineNanos;
    private int nodes;
    private final Map<String, Fetched> cache = new HashMap<>();

    private static final class Fetched {
        Response response;
        String kind = ConfigDecoder.UNKNOWN, note = "", name = "";
        int siteCount;
        List<String[]> children = Collections.emptyList();
        String error = "";
    }

    public ConfigDiscovery(Fetcher fetcher) { this(fetcher, new Options()); }
    public ConfigDiscovery(Fetcher fetcher, Options o) {
        if (fetcher == null || o == null || o.maxDepth < 0 || o.maxChildren < 1
            || o.maxNodes < 1 || o.maxPages < 1 || o.deadlineMs < 1 || o.deadlineMs > 86_400_000)
            throw new IllegalArgumentException("Invalid fetcher/options");
        this.fetcher = fetcher; maxDepth = o.maxDepth; maxChildren = o.maxChildren;
        maxNodes = o.maxNodes; maxPages = o.maxPages; deadlineMs = o.deadlineMs;
    }
    public void cancel() { cancelled = true; }

    public synchronized Result scan(List<Seed> seeds, List<String> sourcePages) throws JSONException {
        cancelled = false; nodes = 0; cache.clear();
        deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000L;
        LinkedHashMap<String, Seed> candidates = new LinkedHashMap<>();
        Map<String, String> pageErrors = new LinkedHashMap<>();
        if (seeds != null) for (Seed seed : seeds) if (seed != null) addSeed(candidates, seed);
        int pageCount = 0;
        if (sourcePages != null) for (String raw : sourcePages) {
            if (stopped() || pageCount++ >= maxPages) break;
            String url = normalizeUrl(raw);
            if (url == null) { pageErrors.put(String.valueOf(raw), "非法页面地址"); continue; }
            try {
                Response r = checkedFetch(url, 6000);
                if (!ConfigDecoder.isBinary(r.body)) {
                    for (String candidate : extractUrls(ConfigDecoder.decodeText(r.body, r.contentType)))
                        addSeed(candidates, new Seed("", candidate));
                }
            } catch (Exception e) { pageErrors.put(url, message(e)); }
        }
        List<Node> roots = new ArrayList<>();
        for (Seed seed : candidates.values()) {
            if (stopped() || nodes >= maxNodes) break;
            roots.add(visit(seed.name, seed.url, 0, new HashSet<String>()));
        }
        return new Result(roots, pageErrors, export(roots), stopped());
    }

    private void addSeed(Map<String, Seed> out, Seed seed) {
        String url = normalizeUrl(seed.url);
        if (url != null && !out.containsKey(url) && out.size() < maxNodes) out.put(url, new Seed(seed.name, url));
    }

    private Node visit(String name, String url, int depth, Set<String> ancestors) {
        Node node = new Node(); node.name = name; node.url = url; node.depth = depth;
        if (stopped()) { node.error = "已取消或达到截止时间"; return node; }
        if (nodes >= maxNodes) { node.error = "超过节点预算"; return node; }
        nodes++;
        if (ancestors.contains(url)) { node.error = "循环引用"; return node; }
        Fetched f = cache.get(url);
        if (f == null) {
            f = new Fetched();
            try {
                Response response = checkedFetch(url, depth == 0 ? 9000 : depth == 1 ? 11000 : 13000);
                ConfigDecoder.Result decoded = ConfigDecoder.decode(response.body, response.contentType);
                String normalizedFinalUrl = normalizeUrl(response.finalUrl);
                // Retain metadata, not every downloaded body/large provider configuration.
                f.response = new Response(new byte[0], response.contentType, normalizedFinalUrl, response.status, response.totalMs);
                if (!decoded.isConfig()) f.error = decoded.error;
                else {
                    f.kind = decoded.kind; f.note = decoded.note;
                    f.name = decoded.object.optString("name", "");
                    f.siteCount = ConfigDecoder.siteCount(decoded.object);
                    List<String[]> allChildren = ConfigDecoder.children(decoded.object, decoded.kind);
                    f.children = new ArrayList<>();
                    for (String[] child : allChildren) {
                        if (f.children.size() >= maxChildren) break;
                        String resolved = resolveUrl(normalizedFinalUrl, child[1]);
                        if (resolved != null) f.children.add(new String[]{child[0], resolved});
                    }
                }
            } catch (Exception e) { f.error = message(e); }
            // Cache immutable parsing outcomes, never mutable Node trees.
            cache.put(url, f);
        }
        if (!f.error.isEmpty()) { node.error = f.error; return node; }
        String finalUrl = normalizeUrl(f.response.finalUrl);
        if (finalUrl == null) { node.error = "非法重定向终点"; return node; }
        if (!finalUrl.equals(url) && ancestors.contains(finalUrl)) { node.error = "重定向循环引用"; return node; }
        node.finalUrl = finalUrl; node.kind = f.kind; node.note = f.note;
        node.configAvailable = true; node.totalMs = f.response.totalMs;
        node.speedLabel = speedLabel(node.totalMs); node.siteCount = f.siteCount;
        if (node.name.isEmpty()) node.name = f.name;
        if (ConfigDecoder.SINGLE.equals(node.kind)) return node;
        if (depth >= maxDepth) { node.error = "达到最大嵌套深度，未展开"; return node; }
        Set<String> path = new HashSet<>(ancestors); path.add(url); path.add(finalUrl);
        int children = 0;
        for (String[] child : f.children) {
            if (stopped()) { node.error = "已停止，部分子仓未展开"; break; }
            if (children >= maxChildren) break;
            if (nodes >= maxNodes) { node.error = "达到节点预算，部分子仓未展开"; break; }
            // Relative config references use the final redirect URL, not the original URL.
            String childUrl = resolveUrl(finalUrl, child[1]);
            if (childUrl == null) continue;
            children++;
            node.children.add(visit(child[0], childUrl, depth + 1, path));
        }
        return node;
    }

    private Response checkedFetch(String url, int timeoutMs) throws Exception {
        if (stopped()) throw new IllegalStateException("已取消或达到截止时间");
        long remainMs = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        Response r = fetcher.fetch(url, (int) Math.min(timeoutMs, remainMs), ConfigDecoder.MAX_BYTES, deadlineNanos);
        if (stopped()) throw new IllegalStateException("已取消或达到截止时间");
        if (r == null || r.body == null) throw new IllegalArgumentException("空 HTTP 响应");
        if (r.status < 200 || r.status >= 300) throw new IllegalArgumentException("HTTP " + r.status);
        if (r.body.length > ConfigDecoder.MAX_BYTES) throw new IllegalArgumentException("响应超过 3 MiB");
        if (normalizeUrl(r.finalUrl) == null) throw new IllegalArgumentException("非法最终地址");
        return r;
    }
    private boolean stopped() { return cancelled || System.nanoTime() >= deadlineNanos; }
    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }
    public static String speedLabel(long ms) { return ms < 0 ? "未测" : ms < 500 ? "快" : ms < 2000 ? "中" : ms < 5000 ? "慢" : "很慢"; }

    public static List<String> extractUrls(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = URL_RE.matcher(text);
        while (m.find() && out.size() < 60) {
            String url = m.group().replaceAll("[.,;:，。；：、)\\]\"}>]+$", "");
            String lower = url.toLowerCase(Locale.ROOT);
            if (SKIP_SUFFIX.matcher(lower).find() || containsAny(lower, SKIP)
                || lower.contains(".html") || lower.contains("/app/") || lower.contains("/soft") || lower.contains("/zt")) continue;
            if (containsAny(lower, LINE) && !out.contains(url)) out.add(url);
        }
        return out;
    }
    private static boolean containsAny(String s, String[] values) { for (String v : values) if (s.contains(v)) return true; return false; }

    /** Syntax normalization only. Public-IP/DNS enforcement belongs to Fetcher. */
    public static String normalizeUrl(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\u200b", "").replace("\ufeff", "").replace(" ", "%20");
        try {
            // Only unwrap exact known proxy HOSTS, not lookalike/subdomain strings.
            URI outer = new URI(s);
            String h = outer.getHost();
            if (h != null) for (String proxy : PROXY) if (proxy.equalsIgnoreCase(h)) {
                String nested = outer.getRawPath();
                if (nested == null || nested.length() <= 1) return null;
                nested = nested.substring(1);
                if (outer.getRawQuery() != null) nested += "?" + outer.getRawQuery();
                s = nested.startsWith("http://") || nested.startsWith("https://") ? nested : "https://" + nested;
                break;
            }
            while (s.matches("(?i)^https?://https?://.*")) s = s.substring(s.indexOf("://") + 3);
            URI u = new URI(s);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            String authority = u.getRawAuthority();
            if (authority == null || authority.isEmpty() || authority.contains("@") || authority.contains("%") || authority.startsWith("[")) return null;
            int colon = authority.lastIndexOf(':');
            String host = colon < 0 ? authority : authority.substring(0, colon);
            int port = colon < 0 ? -1 : Integer.parseInt(authority.substring(colon + 1));
            if (port != -1 && (port < 1 || port > 65535)) return null;
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            if (host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".internal")) return null;
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String query = u.getRawQuery() == null ? "" : "?" + u.getRawQuery();
            String normalized = scheme.toLowerCase(Locale.ROOT) + "://" + host + (port < 0 ? "" : ":" + port) + path + query;
            return new URI(normalized).normalize().toASCIIString();
        } catch (Exception ignored) { return null; }
    }
    public static String resolveUrl(String finalUrl, String relative) {
        if (relative == null || relative.trim().isEmpty()) return null;
        try { return normalizeUrl(new URI(finalUrl).resolve(relative.trim().replace(" ", "%20")).toString()); }
        catch (Exception ignored) { return null; }
    }

    private static JSONObject export(List<Node> roots) throws JSONException {
        List<Node> singles = new ArrayList<>();
        for (Node root : roots) collect(root, singles);
        Collections.sort(singles, new Comparator<Node>() {
            @Override public int compare(Node a, Node b) {
                long x = a.totalMs < 0 ? Long.MAX_VALUE : a.totalMs, y = b.totalMs < 0 ? Long.MAX_VALUE : b.totalMs;
                return Long.compare(x, y);
            }
        });
        Set<String> seen = new HashSet<>();
        JSONArray urls = new JSONArray();
        for (Node n : singles) if (seen.add(n.url)) {
            JSONObject item = new JSONObject();
            item.put("name", n.name.isEmpty() ? n.url : n.name); item.put("url", n.url); urls.put(item);
        }
        JSONObject out = new JSONObject(); out.put("urls", urls); return out;
    }
    private static void collect(Node n, List<Node> out) {
        if (n.configAvailable && ConfigDecoder.SINGLE.equals(n.kind)) out.add(n);
        for (Node child : n.children) collect(child, out);
    }

    public static List<Seed> seedsFromJson(JSONObject sourceList) {
        List<Seed> out = new ArrayList<>(); JSONArray a = sourceList.optJSONArray("base_lines");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item != null) out.add(new Seed(item.optString("name", ""), item.optString("url", "")));
        }
        return out;
    }
    public static List<String> pagesFromJson(JSONObject sourceList) {
        List<String> out = new ArrayList<>(); JSONArray a = sourceList.optJSONArray("search_pages");
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i, ""));
        return out;
    }
}
