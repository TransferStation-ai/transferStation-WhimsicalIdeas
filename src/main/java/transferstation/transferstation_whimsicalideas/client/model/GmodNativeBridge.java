package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class GmodNativeBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean nativeLoaded = false;
    private static boolean initAttempted = false;

    private static Path extractedDllPath = null;

    // 记录最后一次加载失败的原因，便于在配置界面或日志中展示，而无需解析堆栈
    private static String lastLoadError = null;

    public static synchronized boolean isAvailable() {
        return nativeLoaded;
    }

    /**
     * 返回最后一次原生库加载失败的原因；若从未失败则返回 null。
     * Lets the mod surface the reason in a config screen or log without scraping stack traces.
     */
    public static synchronized String getLastLoadError() {
        return lastLoadError;
    }

    public static synchronized boolean tryLoadNative() {
        if (initAttempted) return nativeLoaded;
        initAttempted = true;

        // 1. Extract and load from bundled resources (works in dev and production)
        try {
            if (tryLoadFromResources()) {
                nativeLoaded = true;
            } else {
                lastLoadError = "native DLL not found in bundled resources";
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("[GmodNative] Failed to load native DLL from resources: {}", e.getMessage());
            lastLoadError = "resource DLL load failed: " + e.getMessage();
        }

        // 2. Try build output and dev directories
        if (!nativeLoaded) {
            String[] searchPaths = {
                "build/native/bin/native-renderer",
                "build/native/cmake-build/bin/native-renderer",
                "build/native/cmake-build/Release/native-renderer",
                "natives/windows/native-renderer",
            };
            boolean foundInBuildDir = false;
            for (String path : searchPaths) {
                try {
                    // Relative to game dir
                    Path resolved = FMLPaths.GAMEDIR.get().resolve(path);
                    if (!resolved.toString().endsWith(".dll")) {
                        resolved = Path.of(resolved.toString() + ".dll");
                    }
                    if (Files.exists(resolved)) {
                        System.load(resolved.toAbsolutePath().toString());
                        nativeLoaded = true;
                        break;
                    }
                    // Relative to working directory
                    resolved = Path.of(path);
                    if (!resolved.toString().endsWith(".dll")) {
                        resolved = Path.of(resolved.toString() + ".dll");
                    }
                    if (Files.exists(resolved)) {
                        System.load(resolved.toAbsolutePath().toString());
                        nativeLoaded = true;
                        break;
                    }
                } catch (UnsatisfiedLinkError e) {
                    foundInBuildDir = true;
                    lastLoadError = "build-dir DLL load failed: " + e.getMessage();
                }
            }
            if (!nativeLoaded && !foundInBuildDir) {
                lastLoadError = "native-renderer.dll not found in build/dev directories";
            }
        }

        // 3. Try system library path
        if (!nativeLoaded) {
            try {
                System.loadLibrary("native-renderer");
                nativeLoaded = true;
            } catch (UnsatisfiedLinkError e) {
                lastLoadError = "native-renderer not found on system library path";
            }
        }

        if (nativeLoaded) {
            try {
                boolean ok = nativeInitialize();
                if (ok) {
                    LOGGER.info("[GmodNative] Native renderer initialized successfully");
                } else {
                    LOGGER.error("[GmodNative] Native renderer initialize() returned false");
                    nativeLoaded = false;
                    // Allow a later call to retry initialization instead of being permanently blocked.
                    initAttempted = false;
                    lastLoadError = "nativeInitialize() returned false";
                }
            } catch (UnsatisfiedLinkError e) {
                LOGGER.error("[GmodNative] Native method not found", e);
                nativeLoaded = false;
                // Allow a later call to retry initialization instead of being permanently blocked.
                initAttempted = false;
                lastLoadError = "native method not found: " + e.getMessage();
            }
        } else {
            LOGGER.warn("[GmodNative] native-renderer.dll not found on any search path");
            if (extractedDllPath != null) {
                LOGGER.warn("[GmodNative] DLL was extracted to {} but failed to load. Ensure the DLL was built with MSVC /MT (static CRT) for self-contained deployment, or rebuild with MinGW -static-libgcc -static-libstdc++.", extractedDllPath);
            }
        }

        // 原生渲染器不可用时的明确、显式回退说明（INFO 级别，便于用户直接看到）
        if (!nativeLoaded) {
            String reason = (lastLoadError != null) ? lastLoadError : "library not found";
            LOGGER.info("[GmodNative] Native renderer unavailable — using Java (cross-platform) model parser. Models will still load, possibly slower. Reason: {}", reason);
        }

        return nativeLoaded;
    }

    private static boolean tryLoadFromResources() {
        String[][] resourceDirs = {
            {"/natives/windows/", "native-renderer.dll"},
            {"/assets/transferstation_whimsicalideas/natives/", "native-renderer.dll"},
        };
        // MSVC /MT (static CRT) produces self-contained DLL with no external dependencies
        String[] knownDeps = {};
        try {
            for (String[] dirAndFile : resourceDirs) {
                String dir = dirAndFile[0];
                String mainDllName = dirAndFile[1];
                try (InputStream in = GmodNativeBridge.class.getResourceAsStream(dir + mainDllName)) {
                    if (in == null) continue;
                    Path tempDir = Files.createTempDirectory("gmod_native_");
                    tempDir.toFile().deleteOnExit();
                    extractedDllPath = tempDir.resolve(mainDllName);
                    Files.copy(in, extractedDllPath, StandardCopyOption.REPLACE_EXISTING);
                    extractedDllPath.toFile().deleteOnExit();
                    for (String dep : knownDeps) {
                        try (InputStream depIn = GmodNativeBridge.class.getResourceAsStream(dir + dep)) {
                            if (depIn != null) {
                                Path depPath = tempDir.resolve(dep);
                                Files.copy(depIn, depPath, StandardCopyOption.REPLACE_EXISTING);
                                depPath.toFile().deleteOnExit();
                            }
                        }
                    }
                    // Load with absolute path - dependencies in same dir will be found by Windows loader
                    try {
                        System.load(extractedDllPath.toAbsolutePath().toString());
                        return true;
                    } catch (UnsatisfiedLinkError e) {
                        String msg = e.getMessage() != null ? e.getMessage() : "";
                        // 缺失依赖库（如 VC++ 运行库 / MinGW 的 libgcc/libstdc++）是最常见的失败原因
                        if (msg.contains("dependent libraries") || msg.contains("Can't find") || msg.contains("can't find")) {
                            LOGGER.warn("[GmodNative] Found {} in resources but System.load failed with missing dependent libraries. "
                                    + "The DLL needs its runtime dependencies present alongside it or statically linked. "
                                    + "Either install the matching Visual C++ Redistributable (for MSVC builds) or rebuild the DLL with static linking "
                                    + "(MSVC /MT, or MinGW -static -static-libgcc -static-libstdc++). Underlying error: {}",
                                    mainDllName, msg);
                        }
                        LOGGER.error("[GmodNative] Found {} in resources but failed to load. Ensure the DLL was built with MSVC /MT (static CRT) for self-contained deployment, or rebuild with MinGW -static-libgcc -static-libstdc++.", mainDllName);
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[GmodNative] Failed to extract native DLL from resources", e);
        }
        return false;
    }

    // Native methods - core rendering
    static native boolean nativeInitialize();
    static native long nativeLoadModel(String baseDir, String modelName) throws IOException;
    static native void nativeFreeModel(long handle);
    static native int nativeGetMeshCount(long handle);
    static native void nativeRenderModel(long handle, float[] modelMatrix, int packedLight, float partialTicks);
    static native void nativeRenderModelLOD(long handle, float[] modelMatrix, int packedLight, float partialTicks, int lodLevel);
    static native float nativeGetMinZ(long handle);
    static native float nativeGetModelScale(long handle);
    static native String nativeGetDisplayName(long handle);
    static native void nativeClearAllCaches();

    // Windows-native mesh data extraction methods
    // These extract already-parsed mesh data from a native model handle,
    // allowing construction of SourceModelData without re-parsing in Java.

    // ==================== GPU Skinning Bridge ====================

    static native boolean nativeSkinningAvailable();
    static native boolean nativeSkinningInitialize();
    static native long nativeCreateSkinnedMesh(long modelHandle, int meshIndex);
    static native void nativeDestroySkinnedMesh(long skinHandle);
    static native void nativeSkinAndRenderMesh(long skinHandle, float[] boneMatrices,
                                                int boneCount, float[] modelMatrix,
                                                int packedLight, float[] colorTint);

    /**
     * Get vertex data (float array, 8 floats per vertex: -y, z, x, -ny, nz, nx, u, v)
     * for a specific mesh from a native-loaded model.
     */
    static native float[] nativeGetMeshVertices(long handle, int meshIndex);

    /**
     * Get index data for a specific mesh from a native-loaded model.
     */
    static native int[] nativeGetMeshIndices(long handle, int meshIndex);

    /**
     * Check if a mesh has translucent rendering flag.
     */
    static native boolean nativeIsMeshTranslucent(long handle, int meshIndex);

    /**
     * Check if a mesh has alpha-test rendering flag.
     */
    static native boolean nativeIsMeshAlphaTest(long handle, int meshIndex);

    /**
     * Check if a mesh has no-cull (double-sided) rendering flag.
     */
    static native boolean nativeIsMeshNoCull(long handle, int meshIndex);
}