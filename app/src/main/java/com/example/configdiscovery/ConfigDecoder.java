package com.example.configdiscovery;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Readable reference implementation of the supplied APK's config decoder.
 * Android API 26+ (java.util.Base64); Android's built-in org.json is sufficient.
 * This parses configuration data only. It never executes spider/JAR/JS content.
 */
public final class ConfigDecoder {
    public static final String SINGLE = "single";
    public static final String MULTI_STORE = "multi_storehouse";
    public static final String MULTI_URLS = "multi_urls";
    public static final String UNKNOWN = "unknown";
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 8 * 1024 * 1024;
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9]{8}\\*\\*");

    public static final class Result {
        public JSONObject object;
        public String kind = UNKNOWN;
        public String note = "";
        public String error = "";
        public boolean isConfig() { return object != null && !UNKNOWN.equals(kind); }
    }

    private ConfigDecoder() {}

    public static Result decode(byte[] bytes, String contentType) {
        Result out = new Result();
        if (bytes == null || bytes.length == 0) { out.error = "空响应"; return out; }
        if (bytes.length > MAX_BYTES) { out.error = "响应超过 3 MiB"; return out; }
        String text;
        if (isBinary(bytes)) {
            text = extractBinary(bytes);
            if (text == null) { out.error = "二进制中未提取到配置"; return out; }
            out.note = "二进制内嵌数据";
        } else {
            text = decodeText(bytes, contentType);
        }
        return parseChain(text, out, 0);
    }

    private static Result parseChain(String text, Result out, int depth) {
        if (depth > 4) { out.error = "超过解码层数"; return out; }
        String s = clean(text);
        if (s.isEmpty()) { out.error = "空响应"; return out; }
        JSONObject obj = tryJson(s);
        if (obj != null) return accept(out, obj);
        Matcher m = PREFIX.matcher(s);
        String decoded;
        if (m.find() && m.end() < s.length() && (decoded = base64Text(s.substring(m.end()))) != null) {
            note(out, "Base64(八字符前缀**)");
            return parseChain(decoded, out, depth + 1);
        }
        if (s.startsWith("**") && (decoded = base64Text(s.substring(2))) != null) {
            note(out, "Base64(**)");
            return parseChain(decoded, out, depth + 1);
        }
        if (s.startsWith("2423")) {
            decoded = decrypt2423(s.replaceAll("\\s+", ""));
            if (decoded == null) { out.error = "2423 格式解码失败"; return out; }
            note(out, "AES(2423)");
            return parseChain(decoded, out, depth + 1);
        }
        if (looksBase64(s) && (decoded = base64Text(s)) != null) {
            note(out, "Base64");
            return parseChain(decoded, out, depth + 1);
        }
        int from = 0;
        for (int n = 0; n < 40; n++) {
            int start = s.indexOf('{', from);
            if (start < 0) break;
            from = start + 1;
            String candidate = balancedObject(s, start);
            if (candidate != null && candidate.length() >= 20) {
                obj = tryJson(candidate);
                if (obj != null && !UNKNOWN.equals(classify(obj))) {
                    note(out, "页面内嵌配置");
                    return accept(out, obj);
                }
            }
        }
        out.error = "响应不是可识别的配置";
        return out;
    }

    private static Result accept(Result out, JSONObject obj) {
        out.object = obj;
        out.kind = classify(obj);
        if (UNKNOWN.equals(out.kind)) out.error = "JSON 结构不是单仓或多仓";
        return out;
    }
    private static void note(Result out, String s) { if (out.note.isEmpty()) out.note = s; }

    public static String classify(JSONObject obj) {
        JSONArray a = obj.optJSONArray("storeHouse");
        if (a != null && a.length() > 0) return MULTI_STORE;
        a = obj.optJSONArray("urls");
        if (a != null && a.length() > 0) return MULTI_URLS;
        if (obj.has("sites") || obj.has("lives") || obj.has("parses") || obj.has("spider")) return SINGLE;
        return UNKNOWN;
    }

    public static int siteCount(JSONObject obj) {
        JSONArray a = obj.optJSONArray("sites"), b = obj.optJSONArray("lives");
        return (a == null ? 0 : a.length()) + (b == null ? 0 : b.length());
    }

    /** Each entry is {displayName, url}. Field fallbacks match the APK. */
    public static List<String[]> children(JSONObject obj, String kind) {
        List<String[]> out = new ArrayList<>();
        if (MULTI_STORE.equals(kind)) {
            JSONArray a = obj.optJSONArray("storeHouse");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject child = a.optJSONObject(i);
                if (child != null) out.add(new String[]{
                    child.optString("sourceName", child.optString("name", "")),
                    child.optString("sourceUrl", child.optString("url", child.optString("api", "")))
                });
            }
        }
        JSONArray a = obj.optJSONArray("urls");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject child = a.optJSONObject(i);
            if (child != null) out.add(new String[]{child.optString("name", ""),
                child.optString("url", child.optString("api", ""))});
        }
        return out;
    }

    private static String clean(String text) {
        String s = text == null ? "" : text.replace("\ufeff", "").replace("\u0000", "").trim();
        if (s.startsWith("//") || s.startsWith("/*")) {
            int a = s.indexOf('{'), b = s.indexOf('[');
            int first = a < 0 ? b : b < 0 ? a : Math.min(a, b);
            if (first > 0) s = s.substring(first).trim();
        }
        return s;
    }

    private static JSONObject tryJson(String s) {
        if (s.isEmpty() || (s.charAt(0) != '{' && s.charAt(0) != '[')) return null;
        try {
            Object obj = new JSONTokener(removeTrailingCommas(s.replace('\u00a0', ' '))).nextValue();
            if (obj instanceof JSONObject) return (JSONObject) obj;
        } catch (Exception ignored) {}
        String balanced = balancedObject(s, 0);
        if (balanced != null && balanced.length() != s.length()) {
            try {
                Object obj = new JSONTokener(removeTrailingCommas(balanced)).nextValue();
                if (obj instanceof JSONObject) return (JSONObject) obj;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // Unlike the APK's global regex, this preserves comma/bracket text inside strings.
    private static String removeTrailingCommas(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean quoted = false, escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                out.append(c);
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            if (c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String balancedObject(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') return null;
        boolean quoted = false, escaped = false;
        int depth = 0, end = Math.min(s.length(), start + 2 * 1024 * 1024);
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return null;
    }

    private static boolean looksBase64(String s) {
        s = s.replaceAll("\\s", "");
        return s.length() >= 40 && s.matches("[A-Za-z0-9+/=_-]+");
    }
    private static String base64Text(String s) {
        s = s.replaceAll("\\s", "");
        for (Base64.Decoder decoder : new Base64.Decoder[]{Base64.getUrlDecoder(), Base64.getDecoder()}) {
            try {
                byte[] bytes = decoder.decode(s);
                if (bytes.length == 0) continue;
                String text = new String(gunzip(bytes), StandardCharsets.UTF_8);
                if (printableRatio(text) >= .85) return text;
            } catch (Exception ignored) {}
        }
        return null;
    }
    private static byte[] gunzip(byte[] bytes) throws Exception {
        if (bytes.length < 3 || (bytes[0] & 255) != 31 || (bytes[1] & 255) != 139) return bytes;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("gzip 超过 8 MiB");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 2423 is the hex spelling of "$#"; key and IV are embedded in this container. */
    private static String decrypt2423(String s) {
        try {
            if (s.length() < 60) return null;
            byte[] all = hex(s);
            if (all == null) return null;
            String container = new String(all, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            int keyStart = container.indexOf("$#"), keyEnd = container.indexOf("#$");
            if (keyStart < 0 || keyEnd <= keyStart + 2 || container.length() < 13) return null;
            String key = pad16(container.substring(keyStart + 2, keyEnd));
            String iv = pad16(container.substring(container.length() - 13));
            int delimiter = s.indexOf("2324");
            if (delimiter < 0 || delimiter + 4 >= s.length() - 26) return null;
            byte[] encrypted = hex(s.substring(delimiter + 4, s.length() - 26));
            if (encrypted == null || encrypted.length < 16 || encrypted.length % 16 != 0) return null;
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
            String text = new String(aes.doFinal(encrypted), StandardCharsets.UTF_8);
            return printableRatio(text) > .9 ? text : null;
        } catch (Exception ignored) { return null; }
    }
    private static String pad16(String s) {
        return s.length() >= 16 ? s.substring(0, 16) : s + "0000000000000000".substring(s.length());
    }
    private static byte[] hex(String s) {
        if (s.length() < 32 || s.length() % 2 != 0 || !s.matches("[0-9a-fA-F]+")) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String extractBinary(byte[] bytes) {
        StringBuilder run = new StringBuilder();
        String longest = null;
        for (int i = 0; i <= bytes.length; i++) {
            int c = i < bytes.length ? bytes[i] & 255 : -1;
            if ((c >= 32 && c <= 126) || c == 9 || c == 10 || c == 13) run.append((char) c);
            else {
                if (run.length() >= 40 && (longest == null || run.length() > longest.length())) longest = run.toString();
                run.setLength(0);
            }
        }
        if (longest != null) {
            String s = clean(longest);
            if (s.startsWith("{") || s.startsWith("**") || s.startsWith("2423") || looksBase64(s) || PREFIX.matcher(s).find()) return s;
        }
        // Heuristic over raw FILE bytes, not decoded RGB image pixels.
        for (int bit = 0; bit <= 1; bit++) for (int order = 0; order < 2; order++) {
            int count = Math.min(Math.min(bytes.length, 2 * 1024 * 1024) / 8, 196608);
            byte[] decoded = new byte[count];
            for (int i = 0; i < count; i++) {
                int value = 0;
                for (int j = 0; j < 8; j++) {
                    int b = (bytes[i * 8 + j] >> bit) & 1;
                    value |= b << (order == 0 ? 7 - j : j);
                }
                decoded[i] = (byte) value;
            }
            if (count < 16) continue;
            int start = 0;
            while (start < count && decoded[start] != '{') start++;
            if (start == count) continue;
            String s = new String(decoded, start, count - start, StandardCharsets.UTF_8);
            if (printableRatio(s) > .85) return clean(s);
        }
        return null;
    }

    public static boolean isBinary(byte[] b) {
        boolean image = (b.length >= 4 && (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71)
            || (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255)
            || (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F')
            || (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            || (b.length >= 2 && b[0] == 'B' && b[1] == 'M');
        if (image) return true;
        int n = Math.min(b.length, 1024), zeros = 0;
        for (int i = 0; i < n; i++) if (b[i] == 0) zeros++;
        return n > 0 && zeros > n / 10;
    }

    public static String decodeText(byte[] bytes, String type) {
        if (type != null) {
            Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([^;\\s\"']+)", Pattern.CASE_INSENSITIVE).matcher(type);
            if (m.find()) try { return new String(bytes, Charset.forName(m.group(1))); } catch (Exception ignored) {}
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (Exception ignored) {
            try { return new String(bytes, Charset.forName("GBK")); }
            catch (Exception unavailable) { return new String(bytes, StandardCharsets.UTF_8); }
        }
    }
    private static double printableRatio(String s) {
        int n = Math.min(s.length(), 4096), printable = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != '\ufffd' && ((c >= 32 && c <= 126) || c == '\n' || c == '\r' || c == '\t'
                || (c > 127 && c != '\ufffe' && c != '\uffff'))) printable++;
        }
        return n == 0 ? 0 : (double) printable / n;
    }
}
