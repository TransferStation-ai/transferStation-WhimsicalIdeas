package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

public class LinuxNativeModelParserStrategy implements ModelParserStrategy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final JavaModelParserStrategy fallback = new JavaModelParserStrategy();
    private static Boolean nativeAvailable = null;
    private static String detectedArch = null;

    @Override
    public boolean isAvailable() {
        if (nativeAvailable == null) {
            detectNativeAvailability();
        }
        return nativeAvailable;
    }

    @Override
    public String getPlatformName() {
        if (detectedArch == null) detectNativeAvailability();
        return "Linux Native (" + detectedArch + ", via native-core.so)";
    }

    private static synchronized void detectNativeAvailability() {
        if (nativeAvailable != null) return;

        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            detectedArch = "x86_64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            detectedArch = "aarch64";
        } else if (osArch.contains("arm")) {
            detectedArch = "arm";
        } else {
            detectedArch = osArch.isEmpty() ? "unknown" : osArch;
        }

        if (GmodNativeCoreBridge.tryLoadNative()) {
            nativeAvailable = true;
            LOGGER.info("[LinuxNativeParser] native-core.so available for arch: {}", detectedArch);
        } else {
            nativeAvailable = false;
            LOGGER.info("[LinuxNativeParser] native-core.so not available for arch: {}, using Java fallback", detectedArch);
        }
    }

    @Override
    public MdlDataTypes.ParsedModel parseMdl(byte[] data) {
        if (!isAvailable()) {
            return fallback.parseMdl(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseMdlSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                MdlDataTypes.ParsedModel model = WindowsNativeModelParserStrategy.deserializeParsedModel(nativeResult);
                if (model != null) {
                    LOGGER.debug("[LinuxNativeParser] MDL parsed natively: {} bones, {} meshes",
                        model.header.numbones, model.meshes.size());
                    return model;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[LinuxNativeParser] Native MDL parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[LinuxNativeParser] Native MDL parse failed: {}", e.getMessage());
        }
        return fallback.parseMdl(data);
    }

    @Override
    public VvdParser.ParsedVvd parseVvd(byte[] data) {
        if (!isAvailable()) {
            return fallback.parseVvd(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseVvdSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VvdParser.ParsedVvd vvd = WindowsNativeModelParserStrategy.deserializeParsedVvd(nativeResult);
                if (vvd != null) {
                    LOGGER.debug("[LinuxNativeParser] VVD parsed natively: {} vertices", vvd.vertices.size());
                    return vvd;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[LinuxNativeParser] Native VVD parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[LinuxNativeParser] Native VVD parse failed: {}", e.getMessage());
        }
        return fallback.parseVvd(data);
    }

    @Override
    public VtxParser.ParsedVtx parseVtx(byte[] data) {
        if (!isAvailable()) {
            return fallback.parseVtx(data);
        }
        try {
            byte[] nativeResult = GmodNativeCoreBridge.nativeParseVtxSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VtxParser.ParsedVtx vtx = WindowsNativeModelParserStrategy.deserializeParsedVtx(nativeResult);
                if (vtx != null) {
                    LOGGER.debug("[LinuxNativeParser] VTX parsed natively: {} mesh triangles", vtx.meshTriangles.size());
                    return vtx;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.info("[LinuxNativeParser] Native VTX parse unavailable ({}), fallback", e.getMessage());
            nativeAvailable = false;
        } catch (Exception e) {
            LOGGER.debug("[LinuxNativeParser] Native VTX parse failed: {}", e.getMessage());
        }
        return fallback.parseVtx(data);
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
