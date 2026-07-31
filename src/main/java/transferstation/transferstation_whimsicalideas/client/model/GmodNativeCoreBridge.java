package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public class GmodNativeCoreBridge {

    private static boolean nativeLoaded = false;
    private static boolean initAttempted = false;
    private static String lastLoadError = null;

    // Resource extraction paths for Linux .so
    private static Path extractedLinuxSoPath = null;

    // Platform state
    private static boolean linuxInit = false;
    private static boolean linuxLoaded = false;
    private static String linuxArch = null;

    private static boolean androidInit = false;
    private static boolean androidLoaded = false;
    private static String androidAbi = null;

    public static synchronized boolean isAvailable() {
        return nativeLoaded;
    }

    public static synchronized String getLastLoadError() {
        return lastLoadError;
    }

    public static synchronized boolean tryLoadNative() {
        if (initAttempted) return nativeLoaded;
        initAttempted = true;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            // Windows: core is bundled inside native-renderer.dll, no separate load needed
            nativeLoaded = true;
            LogUtils.getLogger().info("[GmodNativeCore] Windows: core parsing is part of native-renderer.dll");
            return nativeLoaded;
        }

        if (os.contains("linux")) {
            nativeLoaded = tryLoadLinux();
            return nativeLoaded;
        }

        if (os.contains("android")) {
            nativeLoaded = tryLoadAndroid();
            return nativeLoaded;
        }

        lastLoadError = "unsupported OS: " + os;
        LogUtils.getLogger().warn("[GmodNativeCore] {}", lastLoadError);
        return false;
    }

    private static boolean tryLoadLinux() {
        if (linuxInit) return linuxLoaded;
        linuxInit = true;

        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            linuxArch = "x86_64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            linuxArch = "aarch64";
        } else if (osArch.contains("arm")) {
            linuxArch = "arm";
        } else {
            linuxArch = osArch.isEmpty() ? "unknown" : osArch;
        }

        // 1. Try extracting from bundled resources
        if (tryLoadLinuxFromResources()) {
            linuxLoaded = true;
            return true;
        }

        // 2. Try build output directories (dev environment)
        if (tryLoadLinuxFromBuildDir()) {
            linuxLoaded = true;
            return true;
        }

        // 3. Try system library path (e.g. installed system-wide)
        String[] libNames = {
            "native-core-" + linuxArch,
            "native-core",
        };

        for (String libName : libNames) {
            try {
                System.loadLibrary(libName);
                linuxLoaded = true;
                LogUtils.getLogger().info("[GmodNativeCore] native-core.so ({}) Loaded from system library path for Linux (arch={})", libName, linuxArch);
                return true;
            } catch (Throwable t) {
                LogUtils.getLogger().debug("[GmodNativeCore] Failed to load {} from system path: {}", libName, t.getMessage());
            }
        }

        if (!linuxLoaded) {
            lastLoadError = "native-core.so not found for Linux arch " + linuxArch;
            LogUtils.getLogger().warn("[GmodNativeCore] {}; using Java fallback", lastLoadError);
        }
        return linuxLoaded;
    }

    private static boolean tryLoadLinuxFromResources() {
        String[] resourcePaths = {
            "/natives/linux/" + linuxArch + "/native-core.so",
        };
        try {
            for (String resourcePath : resourcePaths) {
                try (InputStream in = GmodNativeCoreBridge.class.getResourceAsStream(resourcePath)) {
                    if (in == null) continue;
                    Path tempDir = Files.createTempDirectory("gmod_native_linux_");
                    tempDir.toFile().deleteOnExit();
                    Path soPath = tempDir.resolve("native-core.so");
                    Files.copy(in, soPath, StandardCopyOption.REPLACE_EXISTING);
                    soPath.toFile().deleteOnExit();
                    extractedLinuxSoPath = soPath;
                    System.load(soPath.toAbsolutePath().toString());
                    LogUtils.getLogger().info("[GmodNativeCore] native-core.so loaded from resources: {}", resourcePath);
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    LogUtils.getLogger().warn("[GmodNativeCore] Found native-core.so in resources but failed to load: {}", e.getMessage());
                    lastLoadError = "resource .so load failed: " + e.getMessage();
                }
            }
        } catch (IOException e) {
            LogUtils.getLogger().debug("[GmodNativeCore] Failed to extract native-core.so from resources", e);
        }
        return false;
    }

    private static boolean tryLoadLinuxFromBuildDir() {
        // Search in build output and source tree locations
        String[] searchPaths = {
            "build/resources/natives/linux/" + linuxArch + "/native-core.so",
            "build/native/linux/cmake-build/libnative-core.so",
            "src/main/resources/natives/linux/" + linuxArch + "/native-core.so",
        };
        for (String path : searchPaths) {
            try {
                Path resolved = Path.of(path);
                if (Files.exists(resolved)) {
                    System.load(resolved.toAbsolutePath().toString());
                    LogUtils.getLogger().info("[GmodNativeCore] native-core.so loaded from build dir: {}", resolved);
                    return true;
                }
            } catch (UnsatisfiedLinkError e) {
                LogUtils.getLogger().debug("[GmodNativeCore] Found .so in build dir but failed to load: {}", e.getMessage());
                lastLoadError = "build-dir .so load failed: " + e.getMessage();
            }
        }
        return false;
    }

    private static boolean tryLoadAndroid() {
        if (androidInit) return androidLoaded;
        androidInit = true;

        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            androidAbi = "arm64-v8a";
        } else if (osArch.contains("arm")) {
            androidAbi = osArch.contains("v7") ? "armeabi-v7a" : "armeabi";
        } else if (osArch.contains("x86_64") || osArch.contains("amd64")) {
            androidAbi = "x86_64";
        } else if (osArch.contains("x86")) {
            androidAbi = "x86";
        } else {
            androidAbi = osArch.isEmpty() ? "unknown" : osArch;
        }

        String[] libNames = {
            "native-core-" + androidAbi,
            "native-core",
        };

        for (String libName : libNames) {
            try {
                System.loadLibrary(libName);
                androidLoaded = true;
                LogUtils.getLogger().info("[GmodNativeCore] native-core.so ({}) Loaded for Android (ABI={})", libName, androidAbi);
                return true;
            } catch (Throwable t) {
                LogUtils.getLogger().debug("[GmodNativeCore] Failed to load {}: {}", libName, t.getMessage());
            }
        }

        if (!androidLoaded) {
            lastLoadError = "native-core.so not found for Android ABI " + androidAbi;
            LogUtils.getLogger().warn("[GmodNativeCore] {}; using Java fallback", lastLoadError);
        }
        return androidLoaded;
    }

    // ── Core parsing native methods ────────────────────────────────
    static native byte[] nativeParseMdlSerialized(byte[] mdlData);
    static native byte[] nativeParseVvdSerialized(byte[] vvdData);
    static native byte[] nativeParseVtxSerialized(byte[] vtxData);
    static native byte[] nativeParsePhySerialized(byte[] phyData);
}
