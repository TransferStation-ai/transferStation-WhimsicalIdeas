package transferstation.transferstation_whimsicalideas.client.model;

import java.nio.file.Path;

public class ModelPackage {

    private String name;
    private Path packageDir;
    private String displayName;
    private String author;
    private float modelScale = 1.0f;
    private java.util.List<String> tags = new java.util.ArrayList<>();
    private java.util.List<String> modelPaths = new java.util.ArrayList<>();

    public ModelPackage(String name, Path packageDir) {
        this.name = name;
        this.packageDir = packageDir;
    }

    public void discover() {
        // Scan for .lua metadata files for display info
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(packageDir)) {
            walk.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".lua"))
                .findFirst().ifPresent(this::parseLuaMetadata);
        } catch (java.io.IOException ignored) {}

        if (displayName == null || displayName.isEmpty()) {
            displayName = name;
        }
    }

    public String getName() { return name; }
    public String getDisplayName() { return displayName != null ? displayName : name; }
    public String getAuthor() { return author; }
    public float getModelScale() { return modelScale; }
    public java.util.List<String> getTags() { return tags; }
    public java.util.List<String> getModelPaths() { return modelPaths; }
    public Path getPackageDir() { return packageDir; }

    private void parseLuaMetadata(Path luaFile) {
        try {
            java.util.List<String> lines = java.nio.file.Files.readAllLines(luaFile);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.startsWith("--")) continue;

                String val;
                val = extractCommentField(line, "DisplayName");
                if (val != null) { displayName = val; continue; }

                val = extractCommentField(line, "Author");
                if (val != null) { author = val; continue; }

                val = extractCommentField(line, "Scale");
                if (val != null) { try { modelScale = Float.parseFloat(val); } catch (NumberFormatException ignored) {} continue; }

                val = extractCommentField(line, "Tags");
                if (val != null) {
                    for (String p : val.split(",")) {
                        String tag = p.trim();
                        if (!tag.isEmpty()) tags.add(tag);
                    }
                    continue;
                }

                val = extractLuaAssign(line, "DisplayName");
                if (val != null) { displayName = val; continue; }

                val = extractLuaAssign(line, "Author");
                if (val != null) { author = val; continue; }

                val = extractLuaAssign(line, "Scale");
                if (val != null) { try { modelScale = Float.parseFloat(val); } catch (NumberFormatException ignored) {} continue; }

                val = extractModelReference(line);
                if (val != null) {
                    modelPaths.add(val);
                }
            }
        } catch (java.io.IOException ignored) {}
    }

    private static String extractModelReference(String line) {
        String[] patterns = {
            "player_manager.AddValidModel(",
            "list.Set(",
        };
        for (String pattern : patterns) {
            int idx = line.indexOf(pattern);
            if (idx < 0) continue;
            String rest = line.substring(idx + pattern.length());
            int commaIdx = findMatchingComma(rest);
            if (commaIdx < 0) continue;
            rest = rest.substring(commaIdx + 1).trim();
            String value = stripQuotes(rest.split("[,)]", 2)[0].trim());
            if (value.isEmpty()) continue;
            String lower = value.toLowerCase();
            if (lower.endsWith(".mdl")) {
                return value;
            }
            else if (lower.startsWith("models/") && (lower.contains(".mdl") || lower.contains("/"))) {
                return value;
            }
        }

        // Extract Model = "..." from NPC definition tables
        int modelFieldIdx = line.indexOf("Model =");
        if (modelFieldIdx < 0) {
            modelFieldIdx = line.indexOf("Model=");
        }
        if (modelFieldIdx >= 0) {
            String rest = line.substring(modelFieldIdx + ("Model =".length())).trim();
            if (rest.startsWith("\"")) {
                int endIdx = findClosingQuote(rest, 0);
                if (endIdx > 0) {
                    String modelPath = rest.substring(1, endIdx).trim();
                    if (!modelPath.isEmpty() && modelPath.toLowerCase().endsWith(".mdl")) {
                        return modelPath;
                    }
                }
            }
        }
        return null;
    }

    private static int findMatchingComma(String s) {
        int parenDepth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') parenDepth++;
            else if (c == ')') parenDepth--;
            else if (c == ',' && parenDepth == 0) return i;
        }
        return -1;
    }

    private static int findClosingQuote(String s, int start) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return -1;
    }

    private static String extractCommentField(String line, String fieldName) {
        int commentIdx = line.indexOf("--@");
        if (commentIdx < 0) return null;
        String comment = line.substring(commentIdx + 3).trim();
        String prefix = fieldName + ":";
        if (comment.startsWith(prefix)) {
            return comment.substring(prefix.length()).trim();
        }
        return null;
    }

    private static String extractLuaAssign(String line, String fieldName) {
        String pattern = fieldName + "=";
        int idx = line.indexOf(pattern);
        if (idx < 0) {
            pattern = fieldName + " =";
            idx = line.indexOf(pattern);
        }
        if (idx < 0) return null;
        String value = line.substring(idx + pattern.length()).trim();
        value = value.replaceAll(";\\s*$", "");
        value = value.replaceAll(",$", "");
        value = stripQuotes(value);
        return value.isEmpty() ? null : value;
    }

    public static String stripQuotes(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        if (s.startsWith("[[") && s.endsWith("]]")) {
            return s.substring(2, s.length() - 2);
        }
        return s;
    }
}
