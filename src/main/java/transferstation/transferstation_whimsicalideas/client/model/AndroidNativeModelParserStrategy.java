package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public class AndroidNativeModelParserStrategy implements ModelParserStrategy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final JavaModelParserStrategy fallback = new JavaModelParserStrategy();
    private static Boolean nativeAvailable = null;
    private static String detectedAbi = null;

    @Override
    public boolean isAvailable() {
        if (nativeAvailable == null) {
            detectNativeAvailability();
        }
        return nativeAvailable;
    }

    @Override
    public String getPlatformName() {
        if (detectedAbi == null) detectNativeAvailability();
        return "Android Native (" + detectedAbi + ", via native-core.so)";
    }

    private static synchronized void detectNativeAvailability() {
        if (nativeAvailable != null) return;

        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            detectedAbi = "arm64-v8a";
        } else if (osArch.contains("arm")) {
            detectedAbi = armeabiV7aCheck() ? "armeabi-v7a" : "arm";
        } else if (osArch.contains("x86_64") || osArch.contains("amd64")) {
            detectedAbi = "x86_64";
        } else if (osArch.contains("x86")) {
            detectedAbi = "x86";
        } else {
            detectedAbi = osArch.isEmpty() ? "unknown" : osArch;
        }

        if (GmodNativeCoreBridge.tryLoadNative()) {
            nativeAvailable = true;
            LOGGER.info("[AndroidNativeParser] native-core.so available for ABI: {}", detectedAbi);
        } else {
            nativeAvailable = false;
            LOGGER.info("[AndroidNativeParser] native-core.so not available for ABI: {}, using Java fallback", detectedAbi);
        }
    }

    private static boolean armeabiV7aCheck() {
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return osArch.contains("v7") || osArch.contains("v8l");
    }

    @Override
    public MdlDataTypes.ParsedModel parseMdl(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseMdl(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseMdlSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                MdlDataTypes.ParsedModel model = WindowsNativeModelParserStrategy.deserializeParsedModel(nativeResult);
                if (model != null) {
                    LOGGER.debug("[AndroidNativeParser] MDL parsed natively: {} bones", model.header.numbones);
                    return model;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[AndroidNativeParser] Native MDL parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[AndroidNativeParser] Native MDL parse failed: {}", e.getMessage());
        }
        return fallback.parseMdl(data);
    }

    @Override
    public VvdParser.ParsedVvd parseVvd(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVvd(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseVvdSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VvdParser.ParsedVvd vvd = WindowsNativeModelParserStrategy.deserializeParsedVvd(nativeResult);
                if (vvd != null) return vvd;
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[AndroidNativeParser] Native VVD parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[AndroidNativeParser] Native VVD parse failed: {}", e.getMessage());
        }
        return fallback.parseVvd(data);
    }

    @Override
    public VtxParser.ParsedVtx parseVtx(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVtx(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseVtxSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VtxParser.ParsedVtx vtx = WindowsNativeModelParserStrategy.deserializeParsedVtx(nativeResult);
                if (vtx != null) return vtx;
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[AndroidNativeParser] Native VTX parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[AndroidNativeParser] Native VTX parse failed: {}", e.getMessage());
        }
        return fallback.parseVtx(data);
    }

    @Override
    public PhyParser.ParsedPhy parsePhy(byte[] data) throws IOException {
        if (!isAvailable()) {
            return PhyParser.parse(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParsePhySerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                PhyParser.ParsedPhy phy = WindowsNativeModelParserStrategy.deserializeParsedPhy(nativeResult);
                if (phy != null) return phy;
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[AndroidNativeParser] Native PHY parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[AndroidNativeParser] Native PHY parse failed: {}", e.getMessage());
        }
        return PhyParser.parse(data);
    }

    @Override
    public SmdParser.ParsedSmd parseSmd(byte[] data) throws IOException {
        return SmdParser.parse(data);
    }

    @Override
    public SourceModelData loadModel(Path packageDir) throws IOException {
        return fallback.loadModel(packageDir);
    }

    @Override
    public void clearCache() {
        fallback.clearCache();
    }
}
