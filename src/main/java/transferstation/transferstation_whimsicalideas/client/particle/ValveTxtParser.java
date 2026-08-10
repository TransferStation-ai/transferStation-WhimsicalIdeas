package transferstation.transferstation_whimsicalideas.client.particle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 Valve 文本 KV 数据（如 particles_manifest.txt、*.txt 粒子描述）：
 * `"key" "value"`、`{`/`}` 块、`//` 注释、tab 分隔。
 * 返回嵌套 Map（块为子 Map，多值键为 List）。
 *
 * <p>顶层约定：文件第一行通常是文件路径（被跳过），其后是 `{` 块正文，
 * 返回值即该块正文 Map。</p>
 */
public final class ValveTxtParser {

    private ValveTxtParser() {}

    public static Map<String, Object> parse(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new LinkedHashMap<>();
        // 顶层：跳过路径 token + '{'，解析块正文
        if (tokens.size() >= 2 && tokens.get(1).equals("{")) {
            Map<String, Object> map = new LinkedHashMap<>();
            parseBlock(tokens, 2, map);
            return map;
        }
        return new LinkedHashMap<>();
    }

    private static int parseBlock(List<String> tokens, int idx, Map<String, Object> map) {
        while (idx < tokens.size() && !tokens.get(idx).equals("}")) {
            String key = tokens.get(idx);
            idx++;
            if (idx >= tokens.size()) break;
            Object value;
            if (tokens.get(idx).equals("{")) {
                Map<String, Object> sub = new LinkedHashMap<>();
                idx = parseBlock(tokens, idx + 1, sub);
                value = sub;
            } else {
                value = tokens.get(idx);
                idx++;
            }
            putMulti(map, key, value);
        }
        if (idx < tokens.size() && tokens.get(idx).equals("}")) idx++; // 消费 '}'
        return idx;
    }

    private static void putMulti(Map<String, Object> map, String key, Object value) {
        Object prev = map.get(key);
        if (prev == null) {
            map.put(key, value);
        } else if (prev instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> raw = (List<Object>) list;
            raw.add(value);
        } else {
            List<Object> list = new ArrayList<>();
            list.add(prev);
            list.add(value);
            map.put(key, list);
        }
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '{') { tokens.add("{"); i++; continue; }
            if (c == '}') { tokens.add("}"); i++; continue; }
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && text.charAt(i) != '"') {
                    char ch = text.charAt(i);
                    if (ch == '\\' && i + 1 < n) { i++; ch = text.charAt(i); }
                    sb.append(ch);
                    i++;
                }
                i++; // 消费闭合引号
                tokens.add(sb.toString());
                continue;
            }
            // 裸 token：直到空白或 '}'/'{'
            StringBuilder sb = new StringBuilder();
            while (i < n && !Character.isWhitespace(text.charAt(i))
                    && text.charAt(i) != '{' && text.charAt(i) != '}') {
                sb.append(text.charAt(i));
                i++;
            }
            tokens.add(sb.toString());
        }
        return tokens;
    }
}
