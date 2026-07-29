package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ModelParserProvider {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final List<ModelParserStrategy> strategies = new ArrayList<>();
    private static ModelParserStrategy activeStrategy = null;
    private static boolean initialized = false;

    public static synchronized ModelParserStrategy getStrategy() {
        if (!initialized) {
            initialize();
        }
        return activeStrategy;
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        JavaModelParserStrategy javaStrategy = new JavaModelParserStrategy();
        strategies.add(javaStrategy);

        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isWindows = osName.contains("win");
        boolean isLinux = osName.contains("nux") || osName.contains("nix");
        boolean isAndroid = osName.contains("android");

        LOGGER.info("[ModelParserProvider] OS: {} arch: {} (isWindows={} isLinux={} isAndroid={})", System.getProperty("os.name"), osArch, isWindows, isLinux, isAndroid);

        // 优先 Windows
        if (isWindows) {
            WindowsNativeModelParserStrategy windowsStrategy = new WindowsNativeModelParserStrategy();
            if (windowsStrategy.isAvailable()) {
                strategies.add(0, windowsStrategy);
                activeStrategy = windowsStrategy;
                LOGGER.info("[ModelParserProvider] Selected Windows Native model parser strategy");
                return;
            } else {
                LOGGER.info("[ModelParserProvider] Windows native parser not available (native-renderer.dll not loaded)");
            }
        }
        // Android/ARM-Linux优先：ARM Linux（如 Raspberry Pi）可能与 Android 架构兼容
        if (isAndroid || (osName.contains("linux") && osArch.contains("arm"))) {
            AndroidNativeModelParserStrategy androidStrategy = new AndroidNativeModelParserStrategy();
            if (androidStrategy.isAvailable()) {
                strategies.add(0, androidStrategy);
                activeStrategy = androidStrategy;
                LOGGER.info("[ModelParserProvider] Selected Android Native model parser strategy");
                return;
            } else {
                LOGGER.info("[ModelParserProvider] Android native parser not available (native-renderer.so not loaded)");
            }
        }
        // Linux优先（非 ARM 架构）
        if (isLinux) {
            LinuxNativeModelParserStrategy linuxStrategy = new LinuxNativeModelParserStrategy();
            if (linuxStrategy.isAvailable()) {
                strategies.add(0, linuxStrategy);
                activeStrategy = linuxStrategy;
                LOGGER.info("[ModelParserProvider] Selected Linux Native model parser strategy");
                return;
            } else {
                LOGGER.info("[ModelParserProvider] Linux native parser not available (native-renderer.so not loaded)");
            }
        }
        // 默认
        activeStrategy = javaStrategy;
        LOGGER.info("[ModelParserProvider] Selected Java model parser strategy (cross-platform)");
    }

    public static synchronized void refresh() {
        initialized = false;
        strategies.clear();
        activeStrategy = null;
        initialize();
    }

    public static synchronized boolean isWindowsNativeActive() {
        return activeStrategy instanceof WindowsNativeModelParserStrategy;
    }

    public static synchronized String getActivePlatformName() {
        if (activeStrategy == null) return "None";
        return activeStrategy.getPlatformName();
    }
}
