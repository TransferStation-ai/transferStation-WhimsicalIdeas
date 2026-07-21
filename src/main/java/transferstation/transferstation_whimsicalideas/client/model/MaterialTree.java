package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VMT 材质树管理器。
 * 负责按路径加载、缓存、继承解析 .vmt 材质文件。
 * 线程安全（ConcurrentHashMap 缓存）。
 */
public class MaterialTree {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // 材质缓存：材质路径 → 编译后的 MaterialConfig
    private final Map<String, MaterialConfig> materialCache = new ConcurrentHashMap<>();
    
    // 原始 VMT 缓存：材质路径 → 解析后的 VmtMaterial
    private final Map<String, VmtParser.VmtMaterial> vmtCache = new ConcurrentHashMap<>();
    
    // 材质基础搜索路径列表
    private final Path[] searchPaths;
    
    private final VmtParser.VmtIncludeResolver resolver;
    
    public MaterialTree(Path... searchPaths) {
        this.searchPaths = searchPaths;
        this.resolver = new VmtParser.VmtIncludeResolver(this::loadRawVmt);
    }
    
    /**
     * 加载并编译材质。
     * 如果已缓存则直接返回。
     */
    public MaterialConfig getOrCompile(String materialPath) {
        return materialCache.computeIfAbsent(normalizePath(materialPath), path -> {
            try {
                VmtParser.VmtMaterial vmt = loadRawVmt(path);
                if (vmt == null) return null;
                VmtParser.VmtMaterial resolved = resolver.resolve(vmt, 8);
                MaterialConfig config = MaterialConfig.fromVmt(resolved);
                LOGGER.debug("[MaterialTree] Compiled material '{}': {} shader, {} passes",
                    path, config.shaderType, config.renderPassCount);
                return config;
            } catch (Exception e) {
                LOGGER.warn("[MaterialTree] Failed to compile material '{}': {}", path, e.getMessage());
                return null;
            }
        });
    }
    
    /**
     * 从搜索路径中加载原始 .vmt 文件。
     */
    private VmtParser.VmtMaterial loadRawVmt(String materialPath) {
        // 先查缓存
        VmtParser.VmtMaterial cached = vmtCache.get(materialPath);
        if (cached != null) return cached;
        
        // 在搜索路径中查找 .vmt 文件
        String vmtPath = materialPath;
        if (!vmtPath.endsWith(".vmt")) vmtPath += ".vmt";
        vmtPath = vmtPath.replace('\\', '/');
        
        for (Path base : searchPaths) {
            Path file = base.resolve(vmtPath);
            if (Files.exists(file)) {
                try {
                    byte[] data = Files.readAllBytes(file);
                    VmtParser.VmtMaterial vmt = VmtParser.parse(data);
                    vmtCache.put(materialPath, vmt);
                    return vmt;
                } catch (IOException e) {
                    LOGGER.warn("[MaterialTree] Failed to read VMT '{}': {}", file, e.getMessage());
                }
            }
        }
        return null;
    }
    
    /**
     * 通过材质名在搜索路径中找到对应的纹理文件。
     * 返回最佳匹配路径，或 null。
     */
    public Path resolveTexturePath(String textureName) {
        if (textureName == null || textureName.isEmpty()) return null;
        String tex = textureName.replace('\\', '/').toLowerCase();
        if (!tex.endsWith(".vtf")) tex += ".vtf";
        
        for (Path base : searchPaths) {
            // 尝试 materials/ + texture 路径
            Path candidate = base.resolve("materials/" + tex);
            if (Files.exists(candidate)) return candidate;
            // 尝试直接路径
            candidate = base.resolve(tex);
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }
    
    public void clearCache() {
        materialCache.clear();
        vmtCache.clear();
    }
    
    private static String normalizePath(String path) {
        if (path == null) return null;
        return path.replace('\\', '/').toLowerCase();
    }
}