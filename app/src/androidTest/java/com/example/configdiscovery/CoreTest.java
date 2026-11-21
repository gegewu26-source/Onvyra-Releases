package com.example.configdiscovery;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline fixture tests. No network calls, media downloads, or APK execution. */
public final class CoreTest {
    private static int assertions;
    private static final String JSON = "{\"name\":\"Fixture\",\"sites\":[{\"name\":\"A\",\"api\":\"https://example.test/api\"}]}";
    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static void check(boolean ok, String name) {
        assertions++;
        if (!ok) throw new AssertionError(name);
    }
    private static ConfigDecoder.Result decode(String s) { return ConfigDecoder.decode(utf8(s), "application/json"); }

    private static final class Fake implements ConfigDiscovery.Fetcher {
        final Map<String, ConfigDiscovery.Response> data = new HashMap<>();
        final Map<String, Integer> calls = new HashMap<>();
        void add(String url, String json, String finalUrl, long ms) {
            data.put(url, new ConfigDiscovery.Response(utf8(json), "application/json", finalUrl, 200, ms));
        }
        @Override public ConfigDiscovery.Response fetch(String url, int timeoutMs, int maxBytes, long deadline) {
            Integer n = calls.get(url); calls.put(url, n == null ? 1 : n + 1);
            ConfigDiscovery.Response r = data.get(url);
            if (r == null) throw new IllegalArgumentException("Missing fixture " + url);
            return r;
        }
    }

    public static void main(String[] args) throws Exception {
        ConfigDecoder.Result r = decode(JSON);
        check(r.isConfig() && r.kind.equals(ConfigDecoder.SINGLE), "plain nested JSON");
        check(ConfigDecoder.siteCount(r.object) == 1, "site count");
        check(!decode("{\"hello\":123}").isConfig(), "unknown JSON rejected");
        check(decode("{\"sites\":[]}").isConfig(), "APK field-presence semantics retained");
        check(decode("\ufeff // comment\n" + JSON).isConfig(), "BOM and leading comment");
        String tricky = "{\"sites\":[],\"note\":\"literal ,} text and \\\"{x}\\\"\",}";
        check(decode(tricky).object.optString("note").equals("literal ,} text and \"{x}\""), "comma fixer preserves strings");
        check(decode("<script>const config = " + JSON + ";</script>").isConfig(), "HTML nested object");
        String b64 = Base64.getEncoder().withoutPadding().encodeToString(utf8(JSON));
        check(decode(b64).isConfig(), "unprefixed unpadded Base64");
        check(decode("**" + b64).isConfig(), "double-star Base64");
        check(decode("ABCDEFGH**" + b64).isConfig(), "8-character Base64 prefix");
        check(decode("**" + Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(JSON))).isConfig(), "URL-safe Base64");
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(zipped)) { gz.write(utf8(JSON)); }
        check(decode("**" + Base64.getEncoder().encodeToString(zipped.toByteArray())).isConfig(), "Base64 plus gzip");
        String wrapped = JSON;
        for (int i = 0; i < 5; i++) wrapped = "**" + Base64.getEncoder().encodeToString(utf8(wrapped));
        check(!decode(wrapped).isConfig(), "decode nesting bound");

        String keyRaw = "MyKey", ivRaw = "AbCdEf1234567";
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(utf8(pad(keyRaw.toLowerCase(java.util.Locale.ROOT))), "AES"),
            new IvParameterSpec(utf8(pad(ivRaw.toLowerCase(java.util.Locale.ROOT)))));
        ByteArrayOutputStream envelope = new ByteArrayOutputStream();
        envelope.write(utf8("$#" + keyRaw + "#$")); envelope.write(aes.doFinal(utf8(JSON))); envelope.write(utf8(ivRaw));
        check(decode(hex(envelope.toByteArray())).isConfig(), "AES2423 mixed-case key/IV lowering");
        check(!decode("2423bad").isConfig(), "malformed AES rejected");

        ByteArrayOutputStream image = new ByteArrayOutputStream();
        image.write(new byte[]{(byte)137,80,78,71,0,0,0,0}); image.write(utf8("ABCDEFGH**" + b64)); image.write(0);
        check(ConfigDecoder.decode(image.toByteArray(), "image/png").isConfig(), "binary appended ASCII/Base64");
        for (int bit = 0; bit < 2; bit++) for (int order = 0; order < 2; order++) {
            byte[] message = utf8(JSON), carrier = new byte[message.length * 8];
            for (int i = 0; i < message.length; i++) for (int j = 0; j < 8; j++)
                carrier[i * 8 + j] = (byte)(((message[i] >> (order == 0 ? 7-j : j)) & 1) << bit);
            check(ConfigDecoder.decode(carrier, "application/octet-stream").isConfig(), "raw bitplane " + bit + "/" + order);
        }
        String chinese = "{\"sites\":[],\"name\":\"测试\"}";
        check(ConfigDecoder.decode(chinese.getBytes(Charset.forName("GBK")), "application/json; charset=GBK")
            .object.optString("name").equals("测试"), "declared GBK charset");
        check(!ConfigDecoder.decode(new byte[ConfigDecoder.MAX_BYTES + 1], "text/plain").isConfig(), "oversize rejected");

        List<String> found = ConfigDiscovery.extractUrls("https://example.test/cfg.json https://github.com/u/tv.json "
            + "https://example.test/a.png https://example.test/docs.html https://example.test/cfg.json， "
            + "https://other.test/api?x=1&y=2");
        check(found.size() == 2 && found.get(1).endsWith("?x=1&y=2"), "extraction filter and dedupe");
        check(ConfigDiscovery.normalizeUrl("HTTPS://EXAMPLE.TEST/A.json?x=AbC&z=%2F#fragment")
            .equals("https://example.test/A.json?x=AbC&z=%2F"), "host lowercasing preserves query/path");
        check(ConfigDiscovery.normalizeUrl("https://example.test?A=1").equals("https://example.test/?A=1"), "host-only query");
        check(ConfigDiscovery.normalizeUrl("https://例子.测试/配置.json").startsWith("https://xn--"), "IDN");
        check(ConfigDiscovery.normalizeUrl("https://gh-proxy.com/https://example.test/a.json?q=1")
            .equals("https://example.test/a.json?q=1"), "known proxy unwrap");
        check(ConfigDiscovery.normalizeUrl("https://gh-proxy.com.evil.test/https://example.test/a.json")
            .startsWith("https://gh-proxy.com.evil.test/"), "proxy lookalike not unwrapped");
        check(ConfigDiscovery.normalizeUrl("https://user:password@example.test/a.json") == null, "userinfo rejected");
        check(ConfigDiscovery.normalizeUrl("file:///tmp/a.json") == null, "non-HTTP rejected");
        check(ConfigDiscovery.normalizeUrl("http://localhost/a.json") == null, "localhost rejected");

        Fake fake = new Fake();
        String root = "https://origin.example/root.json", finalRoot = "https://cdn.example/v2/root.json";
        fake.add(root, "{\"storeHouse\":[{\"sourceName\":\"Named source\",\"sourceUrl\":\"../leaf.json?token=Case\"},"
            + "{\"name\":\"Sub\",\"url\":\"sub.json\"}]}", finalRoot, 30);
        fake.add("https://cdn.example/leaf.json?token=Case", JSON, "https://cdn.example/leaf.json?token=Case", 120);
        fake.add("https://cdn.example/v2/sub.json", "{\"urls\":[{\"name\":\"Duplicate\",\"url\":\"../leaf.json?token=Case\"},"
            + "{\"name\":\"Cycle\",\"url\":\"" + root + "\"}]}", "https://cdn.example/v2/sub.json", 25);
        ConfigDiscovery.Result scan = new ConfigDiscovery(fake).scan(Collections.singletonList(new ConfigDiscovery.Seed("Root", root)), Collections.<String>emptyList());
        check(scan.config.getJSONArray("urls").length() == 1, "export only unique single config");
        check(scan.config.getJSONArray("urls").getJSONObject(0).getString("name").equals("Named source"), "preserved provided name");
        check(fake.calls.get("https://cdn.example/leaf.json?token=Case") == 1, "dedupe fetched config");
        check(!fake.calls.containsKey("https://origin.example/leaf.json?token=Case"), "resolve against final URL");
        check(scan.roots.get(0).children.get(1).children.get(1).error.equals("循环引用"), "ancestor cycle guarded");
        check(scan.roots.get(0).toJson().isNull("search_verified"), "search unknown not failed");
        check(scan.roots.get(0).toJson().isNull("play_verified"), "play unknown not failed");
        check(ConfigDiscovery.speedLabel(6000).equals("很慢"), "slow config not mislabeled unavailable");

        Fake page = new Fake();
        page.add("https://example.test/page", "<p>https://example.test/a.json https://example.test/b.json</p>", "https://example.test/page", 5);
        page.add("https://example.test/a.json", JSON, "https://example.test/a.json", 80);
        page.add("https://example.test/b.json", JSON, "https://example.test/b.json", 20);
        ConfigDiscovery.Result sorted = new ConfigDiscovery(page).scan(Collections.<ConfigDiscovery.Seed>emptyList(), Collections.singletonList("https://example.test/page"));
        check(sorted.config.getJSONArray("urls").getJSONObject(0).getString("url").endsWith("b.json"), "page discovery and latency order");
        ConfigDiscovery.Options options = new ConfigDiscovery.Options(); options.maxDepth = 0;
        ConfigDiscovery.Result limited = new ConfigDiscovery(fake, options).scan(Collections.singletonList(new ConfigDiscovery.Seed("", root)), null);
        check(limited.roots.get(0).configAvailable && limited.roots.get(0).children.isEmpty(), "depth limit retains root config status");
        check(limited.config.getJSONArray("urls").length() == 0, "unexpanded multi not exported as leaf");
        options = new ConfigDiscovery.Options(); options.maxNodes = 2;
        ConfigDiscovery.Result budget = new ConfigDiscovery(fake, options).scan(Collections.singletonList(new ConfigDiscovery.Seed("", root)), null);
        check(budget.roots.get(0).children.size() == 1, "node budget enforced");
        options = new ConfigDiscovery.Options(); options.maxChildren = 1;
        ConfigDiscovery.Result childLimit = new ConfigDiscovery(fake, options).scan(Collections.singletonList(new ConfigDiscovery.Seed("", root)), null);
        check(childLimit.roots.get(0).children.size() == 1, "child limit enforced");
        ConfigDiscovery.Fetcher emptyError = (url, timeout, maxBytes, deadline) -> { throw new Exception(""); };
        ConfigDiscovery.Result failed = new ConfigDiscovery(emptyError).scan(Collections.singletonList(new ConfigDiscovery.Seed("", root)), null);
        check(!failed.roots.get(0).configAvailable && failed.roots.get(0).error.equals("Exception"), "empty exception message remains a failure");
        Fake spacedRedirect = new Fake();
        spacedRedirect.add(root, "{\"urls\":[{\"url\":\"leaf.json\"}]}", "https://cdn.example/dir space/root.json", 5);
        spacedRedirect.add("https://cdn.example/dir%20space/leaf.json", JSON, "https://cdn.example/dir%20space/leaf.json", 10);
        ConfigDiscovery.Result normalizedRedirect = new ConfigDiscovery(spacedRedirect).scan(Collections.singletonList(new ConfigDiscovery.Seed("", root)), null);
        check(normalizedRedirect.config.getJSONArray("urls").length() == 1
            && normalizedRedirect.roots.get(0).finalUrl.equals("https://cdn.example/dir%20space/root.json"), "normalize final URL before child resolution");
        System.out.println("PASS: " + assertions + " offline assertions; no network or APK execution.");
    }
    private static String pad(String s) { return (s + "0000000000000000").substring(0, 16); }
    private static String hex(byte[] b) {
        StringBuilder out = new StringBuilder();
        for (byte x : b) out.append(String.format(java.util.Locale.ROOT, "%02x", x & 255));
        return out.toString();
    }
}
