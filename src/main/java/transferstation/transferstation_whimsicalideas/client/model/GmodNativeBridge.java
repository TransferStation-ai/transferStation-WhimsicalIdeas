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

    public static synchronized boolean isAvailable() {
        return nativeLoaded;
    }

    public static synchronized boolean tryLoadNative() {
        if (initAttempted) return nativeLoaded;
        initAttempted = true;

        // 1. Extract and load from bundled resources (works in dev and production)
        if (tryLoadFromResources()) {
            nativeLoaded = true;
        }

        // 2. Try build output and dev directories
        if (!nativeLoaded) {
            String[] searchPaths = {
                "build/native/bin/native-renderer",
                "build/native/cmake-build/bin/native-renderer",
                "build/native/cmake-build/Release/native-renderer",
                "natives/windows/native-renderer",
            };
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
                } catch (UnsatisfiedLinkError ignored) {
                }
            }
        }

        // 3. Try system library path
        if (!nativeLoaded) {
            try {
                System.loadLibrary("native-renderer");
                nativeLoaded = true;
            } catch (UnsatisfiedLinkError ignored) {
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
                }
            } catch (UnsatisfiedLinkError e) {
                LOGGER.error("[GmodNative] Native method not found", e);
                nativeLoaded = false;
            }
        } else {
            LOGGER.warn("[GmodNative] native-renderer.dll not found on any search path");
            if (extractedDllPath != null) {
                LOGGER.warn("[GmodNative] DLL was extracted to {} but failed to load. Ensure all native runtime dependencies (libunwind.dll, libc++.dll, etc.) are bundled alongside in the resources/natives/windows/ directory, or rebuild the DLL with -static to embed dependencies.", extractedDllPath);
            }
        }

        return nativeLoaded;
    }

    private static boolean tryLoadFromResources() {
        String[][] resourceDirs = {
            {"/natives/windows/", "native-renderer.dll"},
            {"/assets/transferstation_whimsicalideas/natives/", "native-renderer.dll"},
        };
        String[] knownDeps = {"libunwind.dll", "libc++.dll", "libc++abi.dll",
            "libgcc_s_seh-1.dll", "libgcc_s_dw2-1.dll", "libstdc++-6.dll", "libwinpthread-1.dll"};
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
                        LOGGER.error("[GmodNative] Found {} in resources but failed to load. Missing native dependencies (e.g. libunwind.dll, libc++.dll). Extract the compiler runtime DLLs to the same resource directory or rebuild with -static.", mainDllName);
                        throw e;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[GmodNative] Failed to extract native DLL from resources", e);
        }
        return false;
    }

    // Native methods
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
}