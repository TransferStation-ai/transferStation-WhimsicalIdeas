package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class WindowsNativeModelParserStrategy implements ModelParserStrategy {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final JavaModelParserStrategy fallback = new JavaModelParserStrategy();

    private static final int PARSE_MAGIC = 0x574E5053;

    @Override
    public boolean isAvailable() {
        return GmodNativeBridge.isAvailable();
    }

    @Override
    public String getPlatformName() {
        return "Windows Native (via native-renderer.dll)";
    }

    @Override
    public MdlDataTypes.ParsedModel parseMdl(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseMdl(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseMdlSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                MdlDataTypes.ParsedModel model = deserializeParsedModel(nativeResult);
                if (model != null) {
                    LOGGER.debug("[WindowsNativeParser] MDL parsed natively: {} bones, {} meshes, {} body parts",
                        model.header.numbones, model.meshes.size(), model.bodyParts.size());
                    return model;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Native MDL parse failed, falling back to Java: {}", e.getMessage());
        }
        return fallback.parseMdl(data);
    }

    @Override
    public VvdParser.ParsedVvd parseVvd(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVvd(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseVvdSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VvdParser.ParsedVvd vvd = deserializeParsedVvd(nativeResult);
                if (vvd != null) {
                    LOGGER.debug("[WindowsNativeParser] VVD parsed natively: {} vertices", vvd.vertices.size());
                    return vvd;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Native VVD parse failed, falling back to Java: {}", e.getMessage());
        }
        return fallback.parseVvd(data);
    }

    @Override
    public VtxParser.ParsedVtx parseVtx(byte[] data) throws IOException {
        if (!isAvailable()) {
            return fallback.parseVtx(data);
        }
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParseVtxSerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                VtxParser.ParsedVtx vtx = deserializeParsedVtx(nativeResult);
                if (vtx != null) {
                    LOGGER.debug("[WindowsNativeParser] VTX parsed natively: {} mesh triangles", vtx.meshTriangles.size());
                    return vtx;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Native VTX parse failed, falling back to Java: {}", e.getMessage());
        }
        return fallback.parseVtx(data);
    }

    @Override
    public SourceModelData loadModel(Path packageDir) throws IOException {
        if (!isAvailable()) {
            return fallback.loadModel(packageDir);
        }
        try {
            SourceModelData nativeData = loadModelViaNativeBridge(packageDir);
            if (nativeData != null && !nativeData.meshes.isEmpty()) {
                LOGGER.info("[WindowsNativeParser] Model loaded natively from {}: {} meshes, {} triangles",
                    packageDir.getFileName(), nativeData.meshes.size(), nativeData.totalTriangles());
                return nativeData;
            }
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Native model load failed, falling back to Java: {}", e.getMessage());
        }
        return fallback.loadModel(packageDir);
    }

    @Override
    public void clearCache() {
        if (isAvailable()) {
            GmodNativeBridge.nativeClearAllCaches();
        }
        fallback.clearCache();
    }

    public long getNativeHandle(Path packageDir) {
        if (!isAvailable()) return 0;
        try {
            Path mdlFile = findFirstFile(packageDir, ".mdl");
            if (mdlFile == null) return 0;
            String modelName = packageDir.getFileName().toString();
            return GmodNativeBridge.nativeLoadModel(
                packageDir.getParent().toAbsolutePath().toString(),
                modelName
            );
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Failed to get native handle: {}", e.getMessage());
            return 0;
        }
    }

    private SourceModelData loadModelViaNativeBridge(Path packageDir) throws IOException {
        String modelName = packageDir.getFileName().toString();
        long handle = GmodNativeBridge.nativeLoadModel(
            packageDir.getParent().toAbsolutePath().toString(),
            modelName
        );
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

                boolean translucent = GmodNativeBridge.nativeIsMeshTranslucent(handle, i);
                boolean alphaTest = GmodNativeBridge.nativeIsMeshAlphaTest(handle, i);
                boolean noCull = GmodNativeBridge.nativeIsMeshNoCull(handle, i);

                SourceModelData.MeshData mesh = new SourceModelData.MeshData.Builder()
                    .vertices(vertData).indices(idxData)
                    .translucent(translucent).alphaTest(alphaTest).noCull(noCull)
                    .bodyPartIndex(-1).modelIndex(-1).materialIndex(i)
                    .build();

                for (int j = 0; j < vertData.length; j += 8) {
                    float vx = vertData[j];
                    float vy = vertData[j + 1];
                    float vz = vertData[j + 2];
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
                float sizeX = result.maxX - result.minX;
                float sizeY = result.maxY - result.minY;
                float sizeZ = result.maxZ - result.minZ;
                float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
                if (maxDim > 0.001f) {
                    result.modelScale = 1.8f / maxDim;
                }
            }

            return result;
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("[WindowsNativeParser] Native extraction methods not available: {}", e.getMessage());
            return null;
        }
    }

    private float safeGetModelScale(long handle) {
        try {
            return GmodNativeBridge.nativeGetModelScale(handle);
        } catch (Exception e) {
            return 1.0f;
        }
    }

    private static void requireRemaining(ByteBuffer buf, int bytes) {
        if (buf.remaining() < bytes) {
            throw new BufferUnderflowException();
        }
    }

    static MdlDataTypes.ParsedModel deserializeParsedModel(byte[] data) {
        if (data.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != PARSE_MAGIC + 1) return null;

        try {
            MdlDataTypes.ParsedModel result = new MdlDataTypes.ParsedModel();
            result.header = new MdlDataTypes.StudioHeader();
            MdlDataTypes.StudioHeader h = result.header;
            requireRemaining(buf, 4 * 8);
            h.id = buf.getInt();
            h.version = buf.getInt();
            h.checksum = buf.getInt();
            h.name = readString(buf);
            h.numbones = buf.getInt();
            h.numbodyparts = buf.getInt();
            h.numtextures = buf.getInt();
            h.numskinref = buf.getInt();
            h.numskinfamilies = buf.getInt();
            h.flags = buf.getInt();

            requireRemaining(buf, 4);
            int bodyPartCount = buf.getInt();
            for (int i = 0; i < bodyPartCount; i++) {
                MdlDataTypes.StudioBodyPart bp = new MdlDataTypes.StudioBodyPart();
                bp.name = readString(buf);
                requireRemaining(buf, 4 * 2);
                bp.nummodels = buf.getInt();
                bp.baseIndex = buf.getInt();
                result.bodyParts.add(bp);
            }

            requireRemaining(buf, 4);
            int modelCount = buf.getInt();
            for (int i = 0; i < modelCount; i++) {
                MdlDataTypes.StudioModel model = new MdlDataTypes.StudioModel();
                model.name = readString(buf);
                requireRemaining(buf, 4 * 3);
                model.nummeshes = buf.getInt();
                model.numvertices = buf.getInt();
                model.bodypartIndex = buf.getInt();
                result.models.add(model);
            }

            requireRemaining(buf, 4);
            int meshCount = buf.getInt();
            for (int i = 0; i < meshCount; i++) {
                MdlDataTypes.StudioMesh mesh = new MdlDataTypes.StudioMesh();
                requireRemaining(buf, 4 * 4);
                mesh.material = buf.getInt();
                mesh.numvertices = buf.getInt();
                mesh.vertexoffset = buf.getInt();
                mesh.meshid = buf.getInt();
                result.meshes.add(mesh);
            }

            requireRemaining(buf, 4);
            int boneCount = buf.getInt();
            for (int i = 0; i < boneCount; i++) {
                MdlDataTypes.StudioBone bone = new MdlDataTypes.StudioBone();
                bone.name = readString(buf);
                requireRemaining(buf, 4 + 4 * 7);
                bone.parent = buf.getInt();
                bone.pos = new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()};
                bone.quat = new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()};
                result.bones.add(bone);
            }

            requireRemaining(buf, 4);
            int texCount = buf.getInt();
            for (int i = 0; i < texCount; i++) {
                MdlDataTypes.StudioTexture tex = new MdlDataTypes.StudioTexture();
                tex.name = readString(buf);
                requireRemaining(buf, 4);
                tex.flags = buf.getInt();
                result.textures.add(tex);
            }

            requireRemaining(buf, 4);
            int cdTexCount = buf.getInt();
            for (int i = 0; i < cdTexCount; i++) {
                result.cdTextures.add(readString(buf));
            }

            requireRemaining(buf, 4);
            int skinCount = buf.getInt();
            for (int i = 0; i < skinCount; i++) {
                requireRemaining(buf, 4);
                result.skinTable.add(buf.getInt());
            }

            requireRemaining(buf, 4);
            int includeCount = buf.getInt();
            for (int i = 0; i < includeCount; i++) {
                result.includeModels.add(readString(buf));
            }

            return result;
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Failed to deserialize native MDL data: {}", e.getMessage());
            return null;
        }
    }

    static VvdParser.ParsedVvd deserializeParsedVvd(byte[] data) {
        if (data.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != PARSE_MAGIC + 2) return null;

        try {
            VvdParser.ParsedVvd result = new VvdParser.ParsedVvd();
            result.header = new VvdParser.VvdHeader();
            result.header.id = buf.getInt();
            result.header.version = buf.getInt();
            result.header.checksum = buf.getInt();
            result.header.numLODs = buf.getInt();

            int vertexCount = buf.getInt();
            for (int i = 0; i < vertexCount; i++) {
                VvdParser.StudioVertexExt v = new VvdParser.StudioVertexExt();
                v.x = buf.getFloat();
                v.y = buf.getFloat();
                v.z = buf.getFloat();
                v.nx = buf.getFloat();
                v.ny = buf.getFloat();
                v.nz = buf.getFloat();
                v.u = buf.getFloat();
                v.v = buf.getFloat();
                VvdParser.BoneWeight bw = new VvdParser.BoneWeight();
                bw.weight[0] = buf.getFloat();
                bw.weight[1] = buf.getFloat();
                bw.weight[2] = buf.getFloat();
                bw.bone[0] = buf.get() & 0xFF;
                bw.bone[1] = buf.get() & 0xFF;
                bw.bone[2] = buf.get() & 0xFF;
                bw.numbones = buf.get() & 0xFF;
                v.boneWeight = bw;
                result.vertices.add(v);
            }

            return result;
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Failed to deserialize native VVD data: {}", e.getMessage());
            return null;
        }
    }

    static VtxParser.ParsedVtx deserializeParsedVtx(byte[] data) {
        if (data.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != PARSE_MAGIC + 3) return null;

        try {
            VtxParser.ParsedVtx result = new VtxParser.ParsedVtx();
            result.version = buf.getInt();
            result.checksum = buf.getInt();
            result.numBodyParts = buf.getInt();
            result.numLODs = buf.getInt();

            int meshCount = buf.getInt();
            for (int m = 0; m < meshCount; m++) {
                int triCount = buf.getInt();
                List<VtxParser.VtxTriangle> tris = new ArrayList<>(triCount);
                for (int t = 0; t < triCount; t++) {
                    int v0 = buf.getInt();
                    int v1 = buf.getInt();
                    int v2 = buf.getInt();
                    tris.add(new VtxParser.VtxTriangle(v0, v1, v2));
                }
                result.meshTriangles.add(tris);
            }

            return result;
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Failed to deserialize native VTX data: {}", e.getMessage());
            return null;
        }
    }

    public PhyParser.ParsedPhy parsePhy(byte[] data) {
        if (!isAvailable()) return PhyParser.parse(data);
        try {
            byte[] nativeResult = GmodNativeBridge.nativeParsePhySerialized(data);
            if (nativeResult != null && nativeResult.length > 4) {
                PhyParser.ParsedPhy phy = deserializeParsedPhy(nativeResult);
                if (phy != null) return phy;
            }
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Native PHY parse failed, falling back to Java: {}", e.getMessage());
        }
        return PhyParser.parse(data);
    }

    private PhyParser.ParsedPhy deserializeParsedPhy(byte[] data) {
        if (data.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buf.getInt();
        if (magic != PARSE_MAGIC + 4) return null;

        try {
            PhyParser.ParsedPhy result = new PhyParser.ParsedPhy();
            result.size = buf.getInt();
            result.id = readString(buf);
            result.solidCount = buf.getInt();
            result.checksum = buf.getInt();

            int solidCount = buf.getInt();
            for (int i = 0; i < solidCount; i++) {
                PhyParser.PhySolid solid = new PhyParser.PhySolid();
                solid.index = buf.getInt();
                solid.name = readString(buf);
                solid.convexHulls = new ArrayList<>();

                int hullCount = buf.getInt();
                for (int h = 0; h < hullCount; h++) {
                    PhyParser.PhyConvexHull hull = new PhyParser.PhyConvexHull();
                    hull.vertexOffset = buf.getInt();
                    hull.boneIndex = buf.getInt();
                    hull.flags = buf.getInt();
                    hull.triangleCount = buf.getInt();

                    int triCount = buf.getInt();
                    hull.triangles = new ArrayList<>(triCount);
                    for (int t = 0; t < triCount; t++) {
                        PhyParser.PhyTriangle tri = new PhyParser.PhyTriangle();
                        tri.vertexIndex = buf.get() & 0xFF;
                        tri.v1 = buf.getShort() & 0xFFFF;
                        tri.v2 = buf.getShort() & 0xFFFF;
                        tri.v3 = buf.getShort() & 0xFFFF;
                        hull.triangles.add(tri);
                    }

                    int vertCount = buf.getInt();
                    hull.vertices = new ArrayList<>(vertCount);
                    for (int v = 0; v < vertCount; v++) {
                        PhyParser.PhyVertex vert = new PhyParser.PhyVertex();
                        vert.x = buf.getFloat();
                        vert.y = buf.getFloat();
                        vert.z = buf.getFloat();
                        hull.vertices.add(vert);
                    }

                    solid.convexHulls.add(hull);
                }
                result.solids.add(solid);
            }

            result.valid = true;
            return result;
        } catch (Exception e) {
            LOGGER.debug("[WindowsNativeParser] Failed to deserialize native PHY data: {}", e.getMessage());
            return null;
        }
    }

    private Path findFirstFile(Path dir, String extension) {
        try (var files = java.nio.file.Files.walk(dir, 4)) {
            return files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(extension))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static String readString(ByteBuffer buf) {
        int len = buf.getShort() & 0xFFFF;
        if (len == 0) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
