package transferstation.transferstation_whimsicalideas.client.model;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class VmtParser {

    public enum ShaderType {
        VERTEX_LIT_GENERIC,
        UNLIT_GENERIC,
        EYE_REFRACT,
        SPRITE,
        CABLE,
        SKYBOX,
        TOOL_TEXTURE,
        UNKNOWN;

        public static ShaderType fromName(String name) {
            if (name == null) return UNKNOWN;
            String lower = name.trim().toLowerCase();
            if (lower.contains("vertexlitgeneric")) return VERTEX_LIT_GENERIC;
            if (lower.contains("unlitgeneric")) return UNLIT_GENERIC;
            if (lower.contains("eyerefract")) return EYE_REFRACT;
            if (lower.contains("sprite")) return SPRITE;
            if (lower.contains("cable")) return CABLE;
            if (lower.contains("skybox")) return SKYBOX;
            if (lower.contains("tooltexture") || lower.contains("tools/tool")) return TOOL_TEXTURE;
            return UNKNOWN;
        }
    }

    public static class VmtMaterial {
        public String shader;
        public ShaderType shaderType = ShaderType.UNKNOWN;
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
                return btNorm;
            }
            // No $cdmaterials: if the path looks absolute (starts with a known category), return as-is
            if (btNorm.startsWith("models/") || btNorm.startsWith("materials/")
                || btNorm.startsWith("nature/") || btNorm.startsWith("decals/")
                || btNorm.startsWith("effects/") || btNorm.startsWith("editor/")
                || btNorm.startsWith("vgui/") || btNorm.startsWith("skybox/")
                || btNorm.startsWith("overlays/") || btNorm.startsWith("particle/")
                || btNorm.startsWith("lights/") || btNorm.startsWith("map/")
                || btNorm.startsWith("console/") || btNorm.startsWith("ui/")) {
                return btNorm;
            }
            return btNorm;
        }

        public String getBumpMap() {
            return parameters.get("$bumpmap");
        }

        public String getBumpMap2() {
            return parameters.get("$bumpmap2");
        }

        public String getDetail() {
            return parameters.get("$detail");
        }

        public String getDetailScale() {
            return parameters.get("$detailscale");
        }

        public float getDetailBlendFactor() {
            String val = parameters.get("$detailblendfactor");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public String getLightWarpTexture() {
            return parameters.get("$lightwarptexture");
        }

        public String getEnvMap() {
            return parameters.get("$envmap");
        }

        public float getEnvMapContrast() {
            String val = parameters.get("$envmapcontrast");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public float getEnvMapSaturation() {
            String val = parameters.get("$envmapsaturation");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public String getRefractTexture() {
            return parameters.get("$refracttexture");
        }

        public float getRefractAmount() {
            String val = parameters.get("$refractamount");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.0f;
        }

        public String getNormalMap() {
            return parameters.get("$normalmap");
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

        public String getPhongExponentTexture() {
            return parameters.get("$phongexponenttexture");
        }

        public boolean isSelfIllum() {
            return parseBool("$selfillum");
        }

        public boolean isAlphaTest() {
            return parseBool("$alphatest");
        }

        public boolean hasPhong() {
            return parseBool("$phong");
        }

        public boolean isHalfLambert() {
            return parseBool("$halflambert");
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

        public boolean isColorVertex() {
            String val = parameters.get("$color");
            return val != null && val.toLowerCase().contains("vertex");
        }

        public boolean isColor2Vertex() {
            String val = parameters.get("$color2");
            return val != null && val.toLowerCase().contains("vertex");
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

        // === Additional Source Engine material parameters ===

        public boolean isDecal() {
            return parseBool("$decal");
        }

        public boolean isNoExpand() {
            return parseBool("$noexpand");
        }

        public boolean isNoDecal() {
            return parseBool("$nodecal");
        }

        public boolean isNoFog() {
            return parseBool("$nofog");
        }

        public boolean isIgnoreZ() {
            return parseBool("$ignorez");
        }

        public String getSurfaceProp() {
            return parameters.get("$surfaceprop");
        }

        public String getSsBump() {
            return parameters.get("$ssbump");
        }

        public boolean hasSsBump() {
            return parseBool("$ssbump");
        }

        public boolean hasSelfIllum() {
            return parseBool("$selfillum");
        }

        public String getSelfIllumMask() {
            return parameters.get("$selfillummask");
        }

        public String getEnvMapMask() {
            return parameters.get("$envmapmask");
        }

        public String getParallaxMap() {
            return parameters.get("$parallaxmap");
        }

        public float getParallaxCenter() {
            String val = parameters.get("$parallaxcenter");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.5f;
        }

        public float getParallaxScale() {
            String val = parameters.get("$parallaxscale");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.02f;
        }

        public String getAmbientOcclusionTexture() {
            return parameters.get("$ambientocclusion");
        }

        public String getAoTexture() {
            String ao = parameters.get("$ao");
            return ao != null ? ao : getAmbientOcclusionTexture();
        }

        public boolean hasRimLight() {
            return parseBool("$rimlight");
        }

        public float getRimLightBoost() {
            String val = parameters.get("$rimlightboost");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public float getRimLightExponent() {
            String val = parameters.get("$rimlightexponent");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 4.0f;
        }

        public String getBlendModulateTexture() {
            return parameters.get("$blendmodulatetexture");
        }

        public String getModel() {
            return parameters.get("$model");
        }

        public float getMaxLight() {
            String val = parameters.get("$maxlight");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public float getMinLight() {
            String val = parameters.get("$minlight");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.0f;
        }

        public String getMaterialOverride() {
            return parameters.get("$materialoverride");
        }

        public float getAlpha() {
            String val = parameters.get("$alpha");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public float getOpacity() {
            String val = parameters.get("$opacity");
            if (val != null) {
                try { return Float.parseFloat(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return getAlpha();
        }

        public String getOutput() {
            return parameters.get("%output");
        }

        public String getCompileFlags() {
            return parameters.get("%compileflags");
        }

        public String getKeywords() {
            return parameters.get("%keywords");
        }

        /**
         * Detail blend mode as enum constants matching Source Engine:
         * 0 = MUL, 1 = ADD, 2 = MASK, 3 = OVER
         */
        public int getDetailBlendMode() {
            String val = parameters.get("$detailblendmode");
            if (val != null) {
                try { return Integer.parseInt(val.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0;
        }

        public static String detailBlendModeName(int mode) {
            return switch (mode) {
                case 0 -> "MUL";
                case 1 -> "ADD";
                case 2 -> "MASK";
                case 3 -> "OVER";
                default -> "UNKNOWN";
            };
        }

        /**
         * Check if this material uses vertex color ($color with vertex field).
         */
        public boolean usesVertexColor() {
            String color = parameters.get("$color");
            if (color != null && color.toLowerCase().contains("vertex")) return true;
            String color2 = parameters.get("$color2");
            return color2 != null && color2.toLowerCase().contains("vertex");
        }

        /**
         * Get wireframe mode: 0 = off, 1 = on
         */
        public boolean isWireframe() {
            return parseBool("$wireframe");
        }

        public float getDetailScaleWidth() {
            String val = parameters.get("$detailscale");
            if (val != null) {
                try {
                    String[] parts = val.trim().split("\\s+");
                    return Float.parseFloat(parts[0]);
                } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        public float getDetailScaleHeight() {
            String val = parameters.get("$detailscale");
            if (val != null) {
                try {
                    String[] parts = val.trim().split("\\s+");
                    return Float.parseFloat(parts.length > 1 ? parts[1] : parts[0]);
                } catch (NumberFormatException ignored) {}
            }
            return 1.0f;
        }

        /**
         * Get base texture transform ($basetexturetransform)
         * Returns [scaleX, scaleY, rotDeg, transX, transY] or null
         */
        public float[] getBaseTextureTransform() {
            return parseTextureTransform("$basetexturetransform");
        }

        public float[] getBaseTextureTransformMatrix() {
            String val = parameters.get("$basetexturetransform");
            if (val == null || val.isEmpty()) return null;
            float[] parsed = parseTextureTransform("$basetexturetransform");
            if (parsed == null) return null;
            float scaleX = parsed[0], scaleY = parsed[1];
            float rotDeg = parsed[2], transX = parsed[3], transY = parsed[4];
            // Extract optional center pivot (rotation/scale happen around it)
            float centerX = 0f, centerY = 0f;
            try {
                int ci = val.toLowerCase().indexOf("center");
                if (ci >= 0) {
                    String after = val.substring(ci + 6).trim();
                    String[] nums = extractBracketedNumbers(after);
                    if (nums != null && nums.length >= 2) {
                        centerX = Float.parseFloat(nums[0]);
                        centerY = Float.parseFloat(nums[1]);
                    } else {
                        String[] parts = after.split("\\s+");
                        centerX = Float.parseFloat(parts[0]);
                        centerY = parts.length > 1 ? Float.parseFloat(parts[1]) : 0;
                    }
                }
            } catch (Exception ignored) {
                // Fall back to origin-center transform
            }
            double rad = Math.toRadians(rotDeg);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            return new float[]{
                (float)(scaleX * cos), (float)(scaleX * sin),
                (float)(transX + centerX - scaleX * (centerX * cos + centerY * sin)),
                (float)(-scaleY * sin), (float)(scaleY * cos),
                (float)(transY + centerY - scaleY * (-centerX * sin + centerY * cos))
            };
        }

        /**
         * Get bump map texture transform ($bumpmaptransform)
         */
        public float[] getBumpMapTransform() {
            return parseTextureTransform("$bumpmaptransform");
        }

        private float[] parseTextureTransform(String key) {
            String val = parameters.get(key);
            if (val == null || val.isEmpty()) return null;
            val = val.trim();
            // Format: "center [0.5 0.5] scale [1 1] rotate 0 translate [0 0]"
            float[] result = new float[]{1.0f, 1.0f, 0.0f, 0.0f, 0.0f};
            try {
                int ci = val.toLowerCase().indexOf("center");
                int si = val.toLowerCase().indexOf("scale");
                int ri = val.toLowerCase().indexOf("rotate");
                int ti = val.toLowerCase().indexOf("translate");

                if (si >= 0) {
                    String after = val.substring(si + 5).trim();
                    String[] nums = extractBracketedNumbers(after);
                    if (nums != null && nums.length >= 2) {
                        result[0] = Float.parseFloat(nums[0]);
                        result[1] = Float.parseFloat(nums[1]);
                    } else {
                        String[] parts = after.split("\\s+");
                        result[0] = Float.parseFloat(parts[0]);
                        result[1] = parts.length > 1 ? Float.parseFloat(parts[1]) : result[0];
                    }
                }
                if (ri >= 0) {
                    String after = val.substring(ri + 6).trim();
                    String[] parts = after.split("\\s+");
                    result[2] = Float.parseFloat(parts[0]);
                }
                if (ti >= 0) {
                    String after = val.substring(ti + 9).trim();
                    String[] nums = extractBracketedNumbers(after);
                    if (nums != null && nums.length >= 2) {
                        result[3] = Float.parseFloat(nums[0]);
                        result[4] = Float.parseFloat(nums[1]);
                    } else {
                        String[] parts = after.split("\\s+");
                        result[3] = Float.parseFloat(parts[0]);
                        result[4] = parts.length > 1 ? Float.parseFloat(parts[1]) : 0;
                    }
                }
            } catch (Exception ignored) {
                return null;
            }
            return result;
        }

        private String[] extractBracketedNumbers(String s) {
            s = s.trim();
            int start = s.indexOf('[');
            int end = s.indexOf(']');
            if (start >= 0 && end > start) {
                String inner = s.substring(start + 1, end).trim();
                return inner.split("\\s+");
            }
            start = s.indexOf('{');
            end = s.indexOf('}');
            if (start >= 0 && end > start) {
                String inner = s.substring(start + 1, end).trim();
                return inner.split("\\s+");
            }
            return null;
        }

        private static int[] extractTopColors(BufferedImage image, int count) {
            if (image == null || count <= 0) return null;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;

            // 使用HashMap统计颜色频率，O(n)复杂度
            java.util.Map<Integer, Integer> colorFrequency = new java.util.HashMap<>();
            int step = Math.max(1, Math.min(width, height) / 32);

            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int argb = image.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int color = (r << 16) | (g << 8) | b;
                    colorFrequency.put(color, colorFrequency.getOrDefault(color, 0) + 1);
                }
            }

            // 按频率排序，取前count个
            List<Map.Entry<Integer, Integer>> sorted = colorFrequency.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(count)
                .collect(Collectors.toList());

            int[] topColors = new int[Math.min(count, sorted.size())];
            for (int i = 0; i < topColors.length; i++) {
                topColors[i] = sorted.get(i).getKey() | 0xFF000000;
            }
            return topColors;
        }

        private float[] parseColor(String key) {
            String val = parameters.get(key);
            if (val == null || val.isEmpty()) return null;
            val = val.trim();
            // Support {r g b} syntax and [r g b] syntax
            if (val.startsWith("{") || val.startsWith("[")) {
                val = val.substring(1);
            }
            if (val.endsWith("}") || val.endsWith("]")) {
                val = val.substring(0, val.length() - 1);
            }
            // Check for "vertex" keyword - not a numeric color
            if (val.toLowerCase().contains("vertex")) return null;
            String[] parts = val.trim().split("\\s+");
            if (parts.length >= 3) {
                try {
                    // Support both 0-255 range and 0.0-1.0 range colors
                    float r = Float.parseFloat(parts[0]);
                    float g = Float.parseFloat(parts[1]);
                    float b = Float.parseFloat(parts[2]);
                    // Detect if values are in 0-255 range or 0.0-1.0 range
                    boolean isByteRange = r > 1.01f || g > 1.01f || b > 1.01f;
                    if (isByteRange) {
                        r /= 255.0f;
                        g /= 255.0f;
                        b /= 255.0f;
                    }
                    float a = parts.length >= 4 ? Float.parseFloat(parts[3]) : 1.0f;
                    if (a > 1.01f) a /= 255.0f;
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

    /**
     * VMT 材质继承解析器。
     * 支持 %includematerial 继承链，合并参数。
     */
    public static class VmtIncludeResolver {
        private final java.util.function.Function<String, VmtMaterial> materialLoader;
        
        public VmtIncludeResolver(java.util.function.Function<String, VmtMaterial> materialLoader) {
            this.materialLoader = materialLoader;
        }
        
        /**
         * 解析一个 VMT 材质，追踪其 %includematerial 链并合并参数。
         * @param vmt 已解析的 VmtMaterial 对象
         * @param maxDepth 最大继承深度（防止循环引用）
         * @return 合并了所有父材质参数的新 VmtMaterial
         */
        public VmtMaterial resolve(VmtMaterial vmt, int maxDepth) {
            return resolve(vmt, maxDepth, new java.util.HashSet<>());
        }

        private VmtMaterial resolve(VmtMaterial vmt, int maxDepth, java.util.Set<String> visited) {
            if (maxDepth <= 0) return vmt;
            String include = vmt.parameters.get("%includematerial");
            if (include == null || include.isEmpty()) return vmt;
            if (!visited.add(include)) {
                return vmt;
            }
            VmtMaterial parent = materialLoader.apply(include);
            if (parent == null) return vmt;
            VmtMaterial resolved = resolve(parent, maxDepth - 1, visited);
            VmtMaterial result = new VmtMaterial();
            result.shader = vmt.shader != null ? vmt.shader : resolved.shader;
            result.shaderType = vmt.shaderType != ShaderType.UNKNOWN ? vmt.shaderType : resolved.shaderType;
            result.parameters.putAll(resolved.parameters);
            result.parameters.putAll(vmt.parameters);
            return result;
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
                    material.shaderType = ShaderType.fromName(material.shader);
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
