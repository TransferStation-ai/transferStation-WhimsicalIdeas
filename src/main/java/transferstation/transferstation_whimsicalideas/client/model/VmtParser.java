package transferstation.transferstation_whimsicalideas.client.model;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class VmtParser {

    public static class VmtMaterial {
        public String shader;
        public Map<String, String> parameters = new HashMap<>();

        public String getBaseTexture() {
            return parameters.get("$basetexture");
        }

        public String getCdMaterials() {
            return parameters.get("$cdmaterials");
        }

        public String getFullBaseTexturePath() {
            String bt = getBaseTexture();
            if (bt == null) return null;
            String btNorm = bt.replace('\\', '/').toLowerCase();
            if (btNorm.endsWith(".vtf")) {
                btNorm = btNorm.substring(0, btNorm.length() - 4);
            }
            String cd = getCdMaterials();
            if (cd != null && !cd.isEmpty()) {
                String cdNorm = cd.replace('\\', '/').toLowerCase();
                if (!cdNorm.endsWith("/")) cdNorm += "/";
                if (!btNorm.startsWith(cdNorm)) {
                    return cdNorm + btNorm;
                }
            }
            return btNorm;
        }

        public String getBumpMap() {
            return parameters.get("$bumpmap");
        }

        public String getLightWarpTexture() {
            return parameters.get("$lightwarptexture");
        }

        private boolean parseBool(String key) {
            String val = parameters.get(key);
            return val != null && parseBoolValue(val);
        }

        private static boolean parseBoolValue(String val) {
            if (val == null) return false;
            val = val.trim().toLowerCase();
            return val.equals("1") || val.equals("true") || val.equals("yes") || val.equals("on");
        }

        public boolean isNoCull() {
            return parseBool("$nocull");
        }

        public boolean isTransparent() {
            return parseBool("$translucent");
        }

        public boolean isAlphaTest() {
            return parseBool("$alphatest");
        }

        public boolean hasPhong() {
            return parseBool("$phong");
        }

        public float getPhongBoost() {
            String val = parameters.get("$phongboost");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.0f;
        }

        public float[] getPhongFresnelRanges() {
            String val = parameters.get("$phongfresnelranges");
            if (val != null) {
                String[] parts = val.trim().split("\\s+");
                float[] ranges = new float[Math.min(parts.length, 3)];
                for (int i = 0; i < ranges.length; i++) {
                    try { ranges[i] = Float.parseFloat(parts[i]); } catch (NumberFormatException ignored) {}
                }
                return ranges;
            }
            return new float[]{1.0f, 0.1f, 0.1f};
        }

        public boolean isHalfLambert() {
            return parseBool("$halflambert");
        }

        public String getEnvMap() {
            return parameters.get("$envmap");
        }

        public String getDetail() {
            return parameters.get("$detail");
        }

        public String getDetailScale() {
            return parameters.get("$detailscale");
        }

        public String getDetailBlendFactor() {
            return parameters.get("$detailblendfactor");
        }

        public boolean isEmissive() {
            return parseBool("$emissive");
        }

        public String getControlFlowTexture() {
            return parameters.get("$caustics");
        }

        public float[] getColor2() {
            return parseColor("$color2");
        }

        public float[] getColor() {
            return parseColor("$color");
        }

        private float[] parseColor(String key) {
            String val = parameters.get(key);
            if (val == null || val.isEmpty()) return null;
            val = val.trim();
            if (val.startsWith("[")) {
                val = val.substring(1);
            }
            if (val.endsWith("]")) {
                val = val.substring(0, val.length() - 1);
            }
            String[] parts = val.trim().split("\\s+");
            if (parts.length >= 3) {
                try {
                    float r = Float.parseFloat(parts[0]) / 255.0f;
                    float g = Float.parseFloat(parts[1]) / 255.0f;
                    float b = Float.parseFloat(parts[2]) / 255.0f;
                    float a = parts.length >= 4 ? Float.parseFloat(parts[3]) / 255.0f : 1.0f;
                    r = Math.max(0f, Math.min(1f, r));
                    g = Math.max(0f, Math.min(1f, g));
                    b = Math.max(0f, Math.min(1f, b));
                    a = Math.max(0f, Math.min(1f, a));
                    return new float[]{r, g, b, a};
                } catch (NumberFormatException ignored) {}
            }
            return null;
        }
    }

    public static VmtMaterial parse(byte[] data) throws IOException {
        String content = new String(data, StandardCharsets.UTF_8);
        return parse(content);
    }

    public static VmtMaterial parse(String content) {
        VmtMaterial material = new VmtMaterial();

        BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
        String line;
        int braceDepth = 0;
        boolean inShaderBlock = false;
        boolean inComment = false;
        StringBuilder multiLineParam = null;
        String multiLineKey = null;

        try {
            while ((line = reader.readLine()) != null) {
                // Only strip // comments when they are NOT inside quoted strings
                StringBuilder filtered = new StringBuilder();
                boolean inString = false;
                for (int ci = 0; ci < line.length(); ci++) {
                    char c = line.charAt(ci);
                    if (c == '"') { inString = !inString; filtered.append(c); }
                    else if (c == '/' && ci + 1 < line.length() && line.charAt(ci + 1) == '/' && !inString) { break; }
                    else { filtered.append(c); }
                }
                line = filtered.toString().trim();

                if (line.isEmpty()) {
                    continue;
                }

                if (line.startsWith("{")) {
                    braceDepth++;
                    if (braceDepth == 1 && material.shader != null) {
                        inShaderBlock = true;
                    }
                    continue;
                }

                if (line.startsWith("}")) {
                    braceDepth--;
                    if (braceDepth == 0) {
                        inShaderBlock = false;
                    }
                    continue;
                }

                if (braceDepth == 0 && material.shader == null) {
                    material.shader = unquote(line.trim());
                    continue;
                }

                if (inShaderBlock) {
                    parseParameter(line, material.parameters);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse VMT", e);
        }

        return material;
    }

    private static String unquote(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private static void parseParameter(String line, Map<String, String> parameters) {
        line = line.trim();
        if (line.isEmpty()) return;

        // Handle quoted key-value pairs: "key" "value"
        if (line.startsWith("\"")) {
            int firstEnd = findClosingQuote(line, 1);
            if (firstEnd < 0) return;

            String key = line.substring(1, firstEnd).trim();
            String rest = line.substring(firstEnd + 1).trim();

            if (rest.isEmpty()) return;

            String value;
            if (rest.startsWith("\"")) {
                int secondEnd = findClosingQuote(rest, 1);
                if (secondEnd < 0) {
                    value = rest.substring(1).trim();
                } else {
                    value = rest.substring(1, secondEnd).trim();
                }
            } else {
                value = rest;
                int endIdx = value.indexOf(' ');
                if (endIdx > 0) value = value.substring(0, endIdx);
                int tabIdx = value.indexOf('\t');
                if (tabIdx > 0) value = value.substring(0, tabIdx);
                value = value.trim();
            }

            key = unquote(key);
            value = unquote(value);

            if (!key.isEmpty()) {
                parameters.put(key, value);
            }
            return;
        }

        // Handle unquoted key-value pairs separated by space/tab
        int firstSpace = -1;
        int spaceIdx = line.indexOf(' ');
        int tabIdx = line.indexOf('\t');

        if (spaceIdx >= 0 && tabIdx >= 0) {
            firstSpace = Math.min(spaceIdx, tabIdx);
        } else if (spaceIdx >= 0) {
            firstSpace = spaceIdx;
        } else if (tabIdx >= 0) {
            firstSpace = tabIdx;
        }

        if (firstSpace < 0) {
            return;
        }

        String key = line.substring(0, firstSpace).trim();
        String value = line.substring(firstSpace).trim();

        key = unquote(key);
        value = unquote(value);

        if (!key.isEmpty()) {
            parameters.put(key, value);
        }
    }

    private static int findClosingQuote(String s, int start) {
        int idx = start;
        while (idx < s.length()) {
            if (s.charAt(idx) == '\\' && idx + 1 < s.length()) {
                idx += 2;
                continue;
            }
            if (s.charAt(idx) == '\"') {
                return idx;
            }
            idx++;
        }
        return -1;
    }
}