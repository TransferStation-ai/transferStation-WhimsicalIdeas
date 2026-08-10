package transferstation.transferstation_whimsicalideas.client.particle;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 从 Valve 文本格式的 particles_manifest.txt 动态构建「type id ↔ 系统名」映射。
 *
 * <p>构造器注入 {@code resourceReader}（key → 文本内容）：JUnit 注入假实现；
 * MC 集成注入基于 ResourceManager 的读取函数（路径前缀为
 * {@code valve_content/particles/}）。</p>
 *
 * <p>解析器对缺失/畸形输入一律容错：报警（{@link #failureReported()}）+ 回退名字查找，
 * 不阻断粒子系统本身。</p>
 */
public class ParticleIdRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MANIFEST_KEY = "particles_manifest.txt";

    private final Function<String, String> resourceReader;
    private final Map<Integer, String> idToSystem = new HashMap<>();
    private final Map<String, Integer> systemToId = new HashMap<>();
    private boolean failureReported = false;

    public ParticleIdRegistry(Function<String, String> resourceReader) {
        this.resourceReader = resourceReader;
    }

    /**
     * 构建映射。任何一步失败都吞异常并返回 false（不阻断粒子系统本身）。
     *
     * @return true 表示 manifest 成功读取并至少解析出一个条目
     */
    public boolean build() {
        idToSystem.clear();
        systemToId.clear();
        failureReported = false;

        String manifest;
        try {
            manifest = resourceReader.apply(MANIFEST_KEY);
        } catch (Exception e) {
            LOGGER.error("[ParticleIdRegistry] Failed to read manifest '{}'", MANIFEST_KEY, e);
            failureReported = true;
            return false;
        }
        if (manifest == null) {
            LOGGER.warn("[ParticleIdRegistry] No '{}' found - id lookup disabled, name lookup unaffected",
                MANIFEST_KEY);
            failureReported = true;
            return false;
        }

        List<String> files = collectFiles(ValveTxtParser.parse(manifest));
        if (files.isEmpty()) {
            LOGGER.warn("[ParticleIdRegistry] Manifest has no 'file' entries - no id mapping built");
            failureReported = true;
            return false;
        }

        boolean anyParsed = false;
        for (String file : files) {
            try {
                String content = resourceReader.apply(file);
                if (content == null) {
                    LOGGER.warn("[ParticleIdRegistry] Referenced file '{}' missing", file);
                    continue;
                }
                Map<String, Object> root = ValveTxtParser.parse(content);
                if (parseParticlesId(root, file)) {
                    anyParsed = true;
                }
            } catch (Exception e) {
                LOGGER.error("[ParticleIdRegistry] Failed to parse '{}'", file, e);
            }
        }
        if (!anyParsed) {
            failureReported = true;
        }
        return anyParsed;
    }

    /** 从 manifest 根 Map 收集所有 `file` 键的值（String 或 List&lt;String&gt;） */
    private List<String> collectFiles(Map<String, Object> root) {
        List<String> files = new ArrayList<>();
        Object fileVal = root.get("file");
        if (fileVal instanceof String s) {
            files.add(s);
        } else if (fileVal instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) files.add(s);
            }
        }
        return files;
    }

    /**
     * 在单个粒子描述 txt 的解析结果中找 `Particles` 块，读 `"id" "N"`。
     *
     * @return true 表示成功解析出 id
     */
    private boolean parseParticlesId(Map<String, Object> root, String filePath) {
        Object particles = findParticlesBlock(root);
        if (!(particles instanceof Map<?, ?> block)) {
            return false;
        }
        Object idObj = block.get("id");
        if (!(idObj instanceof String idStr)) {
            return false;
        }
        try {
            int id = Integer.parseInt(idStr.trim());
            idToSystem.put(id, filePath);
            systemToId.put(filePath, id);
            LOGGER.debug("[ParticleIdRegistry] id {} -> {}", id, filePath);
            return true;
        } catch (NumberFormatException e) {
            LOGGER.warn("[ParticleIdRegistry] Invalid particle id '{}' in '{}'", idStr, filePath);
            return false;
        }
    }

    /** 在根 Map（或其子块）中找大小写不敏感的 `Particles` 块 */
    private Object findParticlesBlock(Map<String, Object> root) {
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getKey().equalsIgnoreCase("particles") && e.getValue() instanceof Map<?, ?>) {
                return e.getValue();
            }
        }
        for (Object v : root.values()) {
            if (v instanceof Map<?, ?> sub) {
                for (Map.Entry<?, ?> e : sub.entrySet()) {
                    if (e.getKey() instanceof String key && key.equalsIgnoreCase("particles")
                            && e.getValue() instanceof Map<?, ?>) {
                        return e.getValue();
                    }
                }
            }
        }
        return null;
    }

    public String systemNameForId(int id) {
        return idToSystem.get(id);
    }

    public int idForSystem(String systemName) {
        Integer id = systemToId.get(systemName);
        return id == null ? -1 : id;
    }

    public boolean failureReported() {
        return failureReported;
    }
}
