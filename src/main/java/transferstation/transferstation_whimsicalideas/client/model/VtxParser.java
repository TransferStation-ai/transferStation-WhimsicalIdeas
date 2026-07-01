package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class VtxParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean LOG_VERBOSE = false;

    private static final int MAX_COUNT = 1_000_000;
    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024;

    private static final int VERTEX_SIZE = 9;
    private static final int MESH_HEADER_SIZE = 9;
    private static final int STRIP_GROUP_HEADER_SIZE = 25;
    private static final int STRIP_HEADER_SIZE = 27;
    private static final int STRIP_FLAGS_OFFSET = 18;

    // Vertex_t layout (9 bytes):
    //   byte[3] boneWeightIndex
    //   byte numBones
    //   ushort origMeshVertID
    //   byte[3] boneID

    public static class ParsedVtx {
        public int version;
        public int checksum;
        public int numLODs;
        public int numBodyParts;
        public List<List<VtxTriangle>> meshTriangles = new ArrayList<>();
        public List<List<List<VtxTriangle>>> lodMeshTriangles = new ArrayList<>();
    }

    public static class VtxTriangle {
        public int v0, v1, v2;
        public VtxTriangle(int v0, int v1, int v2) {
            this.v0 = v0; this.v1 = v1; this.v2 = v2;
        }
    }

    public static List<List<VtxTriangle>> getTrianglesForLod(ParsedVtx vtx, int lodLevel) {
        if (lodLevel <= 0 || lodLevel >= vtx.lodMeshTriangles.size()) {
            return vtx.meshTriangles;
        }
        return vtx.lodMeshTriangles.get(lodLevel);
    }

    public static ParsedVtx parse(byte[] data) {
        if (data.length > MAX_FILE_SIZE) {
            throw new RuntimeException("VTX file too large: " + data.length + " bytes");
        }

        if (data.length < 36) {
            throw new RuntimeException("VTX file too small: " + data.length + " bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int fileBaseAddr = 0;

        int version = buf.getInt();
        int vertCacheSize = buf.getInt();
        int maxBonesPerStrip = buf.getShort() & 0xFFFF;
        int maxBonesPerTri = buf.getShort() & 0xFFFF;
        int maxBonesPerVert = buf.getInt();
        int checksum = buf.getInt();
        int numLODs = buf.getInt();
        int materialReplacementListOffset = buf.getInt();
        int numBodyParts = buf.getInt();
        int bodyPartOffset = buf.getInt();

        LOGGER.info("[VtxParser] Starting parse: version={} numLODs={} numBodyParts={} bodyPartOffset=0x{}", version, numLODs, numBodyParts, Integer.toHexString(bodyPartOffset));
        LOGGER.info("[VtxParser] BodyPart header at file offset 0x{}", Integer.toHexString(fileBaseAddr + bodyPartOffset));

        ParsedVtx result = new ParsedVtx();
        result.version = version;
        result.checksum = checksum;
        result.numLODs = numLODs;
        result.numBodyParts = numBodyParts;

        int totalMeshes = 0;
        int maxLODsPerModel = 0;
        int bodyPartAddr = fileBaseAddr + bodyPartOffset;
        for (int bp = 0; bp < numBodyParts; bp++) {
            int bpAddr = bodyPartAddr + bp * 8;
            int numModels = buf.getInt(bpAddr);
            int modelOffset = buf.getInt(bpAddr + 4);
            int modelAddr = bpAddr + modelOffset;

            for (int m = 0; m < numModels; m++) {
                int mAddr = modelAddr + m * 8;
                int numLOD = buf.getInt(mAddr);
                int lodOffset = buf.getInt(mAddr + 4);
                int lodAddr = mAddr + lodOffset;
                if (numLOD > maxLODsPerModel) maxLODsPerModel = numLOD;

                int numLODsToProcess = Math.min(Math.max(numLOD, 1), 4);
                for (int l = 0; l < numLODsToProcess; l++) {
                    int lAddr = lodAddr + l * 8;
                    int numMeshes = buf.getInt(lAddr);
                    int meshOffset = buf.getInt(lAddr + 4);
                    int meshAddr = lAddr + meshOffset;

                    for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
                        int meshHdrAddr = meshAddr + meshIdx * MESH_HEADER_SIZE;
                        if (meshHdrAddr + MESH_HEADER_SIZE > data.length) break;
                        int numStripGroups = buf.getInt(meshHdrAddr);
                        int sgOffset = buf.getInt(meshHdrAddr + 4);
                        if (sgOffset == 0) {
                            if (l == 0) {
                                result.meshTriangles.add(new ArrayList<>());
                                totalMeshes++;
                            }
                            continue;
                        }
                        int sgAddr = meshHdrAddr + sgOffset;

                        List<VtxTriangle> meshTris = new ArrayList<>();

                        int stripGroupLimit = Math.min(numStripGroups, 256);
                        for (int sg = 0; sg < stripGroupLimit; sg++) {
                            int sgHdrAddr = sgAddr + sg * STRIP_GROUP_HEADER_SIZE;
                            if (sgHdrAddr + STRIP_GROUP_HEADER_SIZE > data.length) break;

                            int numVerts = buf.getInt(sgHdrAddr);
                            int vertOff = buf.getInt(sgHdrAddr + 4);
                            int numIndices = buf.getInt(sgHdrAddr + 8);
                            int idxOff = buf.getInt(sgHdrAddr + 12);
                            int numStrips = buf.getInt(sgHdrAddr + 16);
                            int stripOff = buf.getInt(sgHdrAddr + 20);

                            if (numVerts <= 0 || numIndices < 3) continue;
                            if (numVerts > MAX_COUNT || numIndices > MAX_COUNT * 3) continue;

                            // Try both relative-to-sgHdrAddr and absolute file addressing
                            int vertDataAddr = resolveOffset(sgHdrAddr, vertOff, (long) numVerts * VERTEX_SIZE, data.length);
                            if (vertDataAddr < 0) {
                                continue;
                            }
                            int indexDataAddr = resolveOffset(sgHdrAddr, idxOff, (long) numIndices * 2L, data.length);
                            if (indexDataAddr < 0) {
                                continue;
                            }

                            int[] origMeshVertIDs = new int[numVerts];
                            for (int vi = 0; vi < numVerts; vi++) {
                                origMeshVertIDs[vi] = buf.getShort(vertDataAddr + vi * VERTEX_SIZE + 4) & 0xFFFF;
                            }

                            int maxIndices = Math.min(numIndices, 262144);
                            int[] cacheIndices = new int[maxIndices];
                            for (int ii = 0; ii < maxIndices; ii++) {
                                cacheIndices[ii] = buf.getShort(indexDataAddr + ii * 2) & 0xFFFF;
                            }

                            int stripHeadersAddr = resolveOffset(sgHdrAddr, stripOff, (long) numStrips * STRIP_HEADER_SIZE, data.length);
                            if (stripHeadersAddr < 0) {
                                continue;
                            }
                            int triListCount = 0, triStripCount = 0;

                            int stripLimit = Math.min(numStrips, 256);
                            for (int s = 0; s < stripLimit; s++) {
                                int sAddr = stripHeadersAddr + s * STRIP_HEADER_SIZE;
                                if (sAddr + STRIP_HEADER_SIZE > data.length) break;

                                int sNumIndices = buf.getInt(sAddr);
                                int sIndexOffset = buf.getInt(sAddr + 4);
                                int sFlags = buf.get(sAddr + STRIP_FLAGS_OFFSET) & 0xFF;

                                if (sNumIndices < 3) continue;
                                if (sIndexOffset < 0 || sIndexOffset + sNumIndices > maxIndices) continue;

                                boolean isTriList = (sFlags & 0x01) != 0;
                                int triEnd = isTriList ? sNumIndices : sNumIndices - 2;
                                int step = isTriList ? 3 : 1;
                                for (int i = 0; i + 2 < sNumIndices; i += step) {
                                    int ci0, ci1, ci2;
                                    if (isTriList || (i & 1) == 0) {
                                        ci0 = cacheIndices[sIndexOffset + i];
                                        ci1 = cacheIndices[sIndexOffset + i + 1];
                                        ci2 = cacheIndices[sIndexOffset + i + 2];
                                    } else {
                                        ci0 = cacheIndices[sIndexOffset + i + 1];
                                        ci1 = cacheIndices[sIndexOffset + i];
                                        ci2 = cacheIndices[sIndexOffset + i + 2];
                                    }
                                    if (ci0 >= numVerts || ci1 >= numVerts || ci2 >= numVerts) continue;
                                    meshTris.add(new VtxTriangle(
                                        origMeshVertIDs[ci0], origMeshVertIDs[ci1], origMeshVertIDs[ci2]));
                                }
                                if (isTriList) triListCount++;
                                else triStripCount++;
                            }

                            if (triListCount == 0 && triStripCount == 0 && maxIndices >= 3) {
                                for (int i = 0; i + 2 < maxIndices; i += 3) {
                                    int ci0 = cacheIndices[i];
                                    int ci1 = cacheIndices[i + 1];
                                    int ci2 = cacheIndices[i + 2];
                                    if (ci0 >= numVerts || ci1 >= numVerts || ci2 >= numVerts) continue;
                                    meshTris.add(new VtxTriangle(
                                        origMeshVertIDs[ci0], origMeshVertIDs[ci1], origMeshVertIDs[ci2]));
                                }
                            }
                        }

                        if (l == 0) {
                            result.meshTriangles.add(meshTris);
                            totalMeshes++;
                        } else {
                            while (result.lodMeshTriangles.size() <= l) {
                                result.lodMeshTriangles.add(new ArrayList<>());
                            }
                            result.lodMeshTriangles.get(l).add(meshTris);
                        }
                    }
                }
            }
        }

        int totalTris = 0;
        for (List<VtxTriangle> list : result.meshTriangles) {
            totalTris += list.size();
        }
        LOGGER.info("[VtxParser] Parsed: bodyParts={} totalMeshes={} totalTris={}", numBodyParts, totalMeshes, totalTris);
        return result;
    }

    public static List<List<VtxTriangle>> buildTrianglesPerMdlMesh(ParsedVtx vtx, MdlParser.ParsedModel mdl, int vvdVertexCount) {
        List<List<VtxTriangle>> result = new ArrayList<>();

        int vtxMeshCount = vtx.meshTriangles.size();
        int vvdCount = vvdVertexCount;

        if (mdl == null || mdl.meshes.isEmpty()) {
            for (int i = 0; i < vtxMeshCount; i++) {
                result.add(new ArrayList<>());
                for (VtxTriangle tri : vtx.meshTriangles.get(i)) {
                    if (tri.v0 < vvdCount && tri.v1 < vvdCount && tri.v2 < vvdCount) {
                        result.get(i).add(tri);
                    }
                }
            }
            if (result.isEmpty()) result.add(new ArrayList<>());
            return result;
        }

        int mdlMeshCount = mdl.meshes.size();
        int meshCount = Math.min(vtxMeshCount, mdlMeshCount);

        for (int i = 0; i < meshCount; i++) {
            List<VtxTriangle> adjusted = new ArrayList<>();
            int oobCount = 0;
            for (VtxTriangle tri : vtx.meshTriangles.get(i)) {
                if (tri.v0 < vvdCount && tri.v1 < vvdCount && tri.v2 < vvdCount) {
                    adjusted.add(new VtxTriangle(tri.v0, tri.v1, tri.v2));
                } else {
                    oobCount++;
                }
            }
            if (oobCount > 0) {
                LOGGER.warn("[VtxParser] Mesh[{}] filtered {} OOB triangles (vvdVerts={})", i, oobCount, vvdCount);
            }
            result.add(adjusted);
        }

        for (int i = meshCount; i < vtxMeshCount; i++) {
            result.add(new ArrayList<>());
            for (VtxTriangle tri : vtx.meshTriangles.get(i)) {
                if (tri.v0 < vvdCount && tri.v1 < vvdCount && tri.v2 < vvdCount) {
                    result.get(result.size() - 1).add(tri);
                }
            }
        }

        LOGGER.info("[VtxParser] buildTrianglesPerMdlMesh: vtxMeshes={} mdlMeshes={} resultMeshes={}", vtxMeshCount, mdlMeshCount, result.size());

        return result;
    }

    private static void checkBounds(int offset, long size, int bufferLimit, String fieldName) {
        if (offset < 0 || offset > bufferLimit - size) {
            long endPos = (long) offset + size;
            throw new RuntimeException(String.format(
                    "VTX parse error: %s at offset %d (size %d) exceeds buffer limit %d",
                    fieldName, offset, size, bufferLimit));
        }
    }

    private static int resolveOffset(int baseAddr, int relOffset, long dataSize, int bufferLimit) {
        if (relOffset < 0) return -1;

        // Try relative to base address first (standard Source VTX convention)
        int relative = baseAddr + relOffset;
        if (relative >= 0 && relative + dataSize <= bufferLimit) {
            return relative;
        }

        // Fallback: try absolute file offset
        if (relOffset + dataSize <= bufferLimit) {
            if (LOG_VERBOSE) {
                LOGGER.info("[VtxParser] Using absolute offset 0x{} instead of relative (base=0x{})", Integer.toHexString(relOffset), Integer.toHexString(baseAddr));
            }
            return relOffset;
        }
        return -1;
    }
}
