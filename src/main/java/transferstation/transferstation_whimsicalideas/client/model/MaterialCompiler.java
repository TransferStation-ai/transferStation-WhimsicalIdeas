package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 材质编译器：将 MaterialConfig 序列化为 JSON，通过 JNI 传给 C++ 侧编译 Shader。
 * 缓存编译结果（materialId），避免重复编译。
 */
public class MaterialCompiler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    
    // 材质路径 → materialId 缓存
    private static final Map<String, Integer> compiledMaterialCache = new ConcurrentHashMap<>();
    // materialId → 材质路径反向映射（用于引用计数释放）
    private static final Map<Integer, String> materialIdToPath = new ConcurrentHashMap<>();
    
    private static int nextMaterialId = 1;
    
    /**
     * 编译一个材质并返回 materialId。
     * 如果 C++ 编译不可用或失败，返回 0（表示用 Java 回退）。
     */
    public static int compile(MaterialConfig config, String materialPath) {
        if (config == null) return 0;
        
        // 检查缓存
        Integer cached = compiledMaterialCache.get(materialPath);
        if (cached != null) return cached;
        
        // 如果原生桥不可用，返回 0（Java 回退）
        if (!GmodNativeBridge.isAvailable()) return 0;
        
        try {
            // 序列化为 JSON
            String materialJson = GSON.toJson(config);
            
            // 通过 JNI 编译
            int materialId = nativeCompileMaterial(materialJson);
            if (materialId > 0) {
                compiledMaterialCache.put(materialPath, materialId);
                materialIdToPath.put(materialId, materialPath);
                LOGGER.debug("[MaterialCompiler] Compiled material '{}' → id={}", materialPath, materialId);
            }
            return materialId;
        } catch (Exception e) {
            LOGGER.warn("[MaterialCompiler] Compile failed for '{}': {}", materialPath, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 释放材质。
     */
    public static void release(String materialPath) {
        Integer id = compiledMaterialCache.remove(materialPath);
        if (id != null && id > 0 && GmodNativeBridge.isAvailable()) {
            materialIdToPath.remove(id);
            nativeReleaseMaterial(id);
        }
    }
    
    /**
     * 释放所有材质。
     */
    public static void releaseAll() {
        if (GmodNativeBridge.isAvailable()) {
            for (int id : compiledMaterialCache.values()) {
                nativeReleaseMaterial(id);
            }
        }
        compiledMaterialCache.clear();
        materialIdToPath.clear();
    }
    
    // JNI native methods — 实现在 jni_bridge.cpp 中
    private static native int nativeCompileMaterial(String materialJson);
    private static native void nativeReleaseMaterial(int materialId);
}