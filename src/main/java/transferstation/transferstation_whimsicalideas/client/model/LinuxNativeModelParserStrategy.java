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
        return "Linux Native (" + detectedArch + ", via native-renderer.so)";
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

        if (GmodNativeBridge.isAvailableLinux()) {
            nativeAvailable = true;
            LOGGER.info("[LinuxNativeParser] Native .so available for arch: {}", detectedArch);
        } else {
            nativeAvailable = false;
            LOGGER.info("[LinuxNativeParser] Native .so not available for arch: {}, using Java fallback", detectedArch);
        }
    }

    @Override
    public MdlDataTypes.ParsedModel parseMdl(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseMdl(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseMdlSerializedLinux(data);
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
    public VvdParser.ParsedVvd parseVvd(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVvd(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseVvdSerializedLinux(data);
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
    public VtxParser.ParsedVtx parseVtx(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVtx(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseVtxSerializedLinux(data);
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
        if (!isAvailable()) {
            return fallback.loadModel(packageDir);
        }
        try {
            SourceModelData nativeData = loadModelViaNative(packageDir);
            if (nativeData != null && !nativeData.meshes.isEmpty()) {
                LOGGER.info("[LinuxNativeParser] Model loaded natively: {} meshes", nativeData.meshes.size());
                return nativeData;
            }
        } catch (Exception e) {
            LOGGER.debug("[LinuxNativeParser] Native model load failed, fallback: {}", e.getMessage());
        }
        return fallback.loadModel(packageDir);
    }

    private SourceModelData loadModelViaNative(Path packageDir) throws IOException {
        String modelName = packageDir.getFileName().toString();
        long handle = tryNativeLoadModel(packageDir, modelName);
        if (handle == 0) return null;

        try {
            int meshCount = GmodNativeBridge.nativeGetMeshCount(handle);
            if (meshCount <= 0) return null;

            SourceModelData result = new SourceModelData();
            result.name = modelName;
            result.modelScale = safeGetModelScale(handle);

            for (int i = 0; i < meshCount; i++) {
                float[] vertData = GmodNativeBridge.nativeGetMeshVertices(handle, i);
                int[] idxData = GmodNativeBridge.nativeGetMeshIndices(handle, i);
                if (vertData == null || idxData == null || idxData.length < 3) continue;

                SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
                    .vertices(vertData).indices(idxData)
                    .bodyPartIndex(-1).modelIndex(-1).materialIndex(i)
                    .build();

                for (int j = 0; j < vertData.length; j += 8) {
                    float vx = vertData[j], vy = vertData[j + 1], vz = vertData[j + 2];
                    if (vx < result.minX) result.minX = vx;
                    if (vx > result.maxX) result.maxX = vx;
                    if (vy < result.minY) result.minY = vy;
                    if (vy > result.maxY) result.maxY = vy;
                    if (vz < result.minZ) result.minZ = vz;
                    if (vz > result.maxZ) result.maxZ = vz;
                }
                result.meshes.add(mesh);
            }

            if (result.minX < Float.MAX_VALUE) {
                float maxDim = Math.max(result.maxX - result.minX,
                    Math.max(result.maxY - result.minY, result.maxZ - result.minZ));
                if (maxDim > 0.001f) result.modelScale = 1.8f / maxDim;
            }

            return result;
        } finally {
            GmodNativeBridge.nativeFreeModel(handle);
        }
    }

    private long tryNativeLoadModel(Path packageDir, String modelName) {
        try {
            return GmodNativeBridge.nativeLoadModel(
                packageDir.getParent().toAbsolutePath().toString(), modelName);
        } catch (Exception e) {
            LOGGER.debug("[LinuxNativeParser] nativeLoadModel failed: {}", e.getMessage());
            return 0;
        }
    }

    private float safeGetModelScale(long handle) {
        try { return GmodNativeBridge.nativeGetModelScale(handle); }
        catch (Exception e) { return 1.0f; }
    }

    @Override
    public void clearCache() {
        if (isAvailable()) {
            try { GmodNativeBridge.nativeClearAllCaches(); }
            catch (Exception ignored) {}
        }
        fallback.clearCache();
    }
}
