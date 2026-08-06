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

    // GMOD / SDK2013 VTX (version 7) uses the 8-byte Vertex_t layout, identical to v2.
    // (A 9-byte Vertex_t exists in some Valve v7+ tools, but GMOD does NOT use it.)
    // This is NOT a compile-time constant: the stride depends on the file version
    // parsed in parse(), so it is computed per-call there.
    private static final int MESH_HEADER_SIZE_V2 = 8;
    private static final int MESH_HEADER_SIZE_V7 = 9;
    private static final int STRIP_GROUP_HEADER_SIZE = 25;
    private static final int STRIP_HEADER_SIZE = 27;
    private static final int STRIP_FLAGS_OFFSET = 18;
    // D3D strip restart marker: when an index in a triangle strip equals this value,
    // it ends the current strip and starts a new one. Without handling this marker,
    // vertices from disconnected parts of the mesh get connected into giant triangles
    // that appear as "random diagonal lines" across the model surface.
    private static final int STRIP_RESTART_INDEX = 0xFFFF;

    // Vertex_t layout (8 bytes for GMOD / SDK2013 v2 and v7):
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
        if (lodLevel <= 0) {
            return vtx.meshTriangles;
        }
        if (vtx.lodMeshTriangles.isEmpty()) {
            return vtx.meshTriangles;
        }
        // lodMeshTriangles.get(l) holds LOD l's meshes (l >= 1). Clamp to the
        // highest available LOD instead of silently falling back to LOD 0.
        int clamped = Math.min(lodLevel, vtx.lodMeshTriangles.size() - 1);
        return vtx.lodMeshTriangles.get(clamped);
    }

    public static ParsedVtx parse(byte[] data) {
        // When the VVD vertex count is unknown, fall back to the strip-group local count
        // (numVerts) by using a sentinel that never rejects.
        return parse(data, Integer.MAX_VALUE);
    }

    public static ParsedVtx parse(byte[] data, int vvdVertexCount) {
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
        buf.getInt();
        buf.getShort();
        buf.getShort();
        int maxBonesPerVert = buf.getInt();
        int checksum = buf.getInt();
        int numLODs = buf.getInt();
        buf.getInt();
        int numBodyParts = buf.getInt();
        int bodyPartOffset = buf.getInt();

        // Mesh header size vs vertex stride are TWO SEPARATE concerns:
        //
        // 1. Mesh header size: VTX v7 uses a 9-byte mesh header (the extra byte is
        //    a flags field after sgOffset). VTX v2 uses 8-byte headers. Using the
        //    wrong header size causes shifted reads for Mesh[1+] in LODs with >1 mesh
        //    per LOD, producing garbage numStripGroups/sgOffset values that appear
        //    as "StripGroup invalid" warnings when the code reads into vertex data.
        //
        // 2. Vertex stride: VTX v7 has variable-size Vertex_t determined by
        //    maxBonesPerVert from the header (offset 12). The struct layout is:
        //        boneWeight[maxBonesPerVert]  — maxBonesPerVert bytes
        //        numBones                     — 1 byte
        //        origMeshVertID               — 2 bytes
        //        boneID[maxBonesPerVert]      — maxBonesPerVert bytes
        //        total: maxBonesPerVert * 2 + 3
        //    For maxBonesPerVert=3 (most common v7): stride = 9.
        //    VTX v2 always uses 8-byte vertices (boneID[2], boneWeight[3]).
        int meshHeaderSize = (version >= 7) ? MESH_HEADER_SIZE_V7 : MESH_HEADER_SIZE_V2;
        int vertexStride = (version >= 7) ? maxBonesPerVert * 2 + 3 : 8;
        LOGGER.info("[VtxParser] Starting parse: version={} meshHeaderSize={} vertexStride={} numLODs={} numBodyParts={} bodyPartOffset=0x{}", version, meshHeaderSize, vertexStride, numLODs, numBodyParts, Integer.toHexString(bodyPartOffset));
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
            if (bpAddr + 8 > data.length) {
                LOGGER.warn("[VtxParser] BodyPart[{}] address 0x{} exceeds file size", bp, Integer.toHexString(bpAddr));
                break;
            }
            int numModels = buf.getInt(bpAddr);
            int modelOffset = buf.getInt(bpAddr + 4);
            int modelAddr = bpAddr + modelOffset;

            if (numModels < 0 || numModels > 128) {
                LOGGER.warn("[VtxParser] BodyPart[{}] invalid numModels={}", bp, numModels);
                continue;
            }

            if (LOG_VERBOSE && numModels > 0) {
                LOGGER.info("[VtxParser] BodyPart[{}]: numModels={} bpAddr=0x{} modelAddr=0x{}",
                    bp, numModels, Integer.toHexString(bpAddr), Integer.toHexString(modelAddr));
            }

            for (int m = 0; m < numModels; m++) {
                int mAddr = modelAddr + m * 8;
                if (mAddr + 8 > data.length) {
                    LOGGER.warn("[VtxParser] Model[{}] address 0x{} exceeds file size", m, Integer.toHexString(mAddr));
                    break;
                }
                int numLOD = buf.getInt(mAddr);
                int lodOffset = buf.getInt(mAddr + 4);
                int lodAddr = mAddr + lodOffset;
                if (numLOD > maxLODsPerModel) maxLODsPerModel = numLOD;

                if (numLOD < 0 || numLOD > 8) {
                    LOGGER.warn("[VtxParser] Model[{}] invalid numLOD={}", m, numLOD);
                    continue;
                }

                int numLODsToProcess = Math.min(Math.max(numLOD, 1), 4);
                for (int l = 0; l < numLODsToProcess; l++) {
                    // Source Engine VTX LOD header is 8 bytes: {numMeshes, meshOffset}.
                    // Using 12 bytes (wrong) shifts all reads for LOD >= 1 by 4 bytes,
                    // producing garbage numMeshes/meshOffset and empty LOD geometry.
                    int lAddr = lodAddr + l * 8;
                    if (lAddr + 8 > data.length) {
                        LOGGER.warn("[VtxParser] LOD[{}] address 0x{} exceeds file size", l, Integer.toHexString(lAddr));
                        break;
                    }
                    int numMeshes = buf.getInt(lAddr);
                    int meshOffset = buf.getInt(lAddr + 4);
                    int meshAddr = lAddr + meshOffset;

                    if (numMeshes < 0 || numMeshes > 4096) {
                        LOGGER.warn("[VtxParser] LOD[{}] invalid numMeshes={}", l, numMeshes);
                        continue;
                    }

                    if (LOG_VERBOSE && numMeshes > 0) {
                        LOGGER.info("[VtxParser] BP={} Model={} LOD={}: numMeshes={} meshAddr=0x{}",
                            bp, m, l, numMeshes, Integer.toHexString(meshAddr));
                    }

                    for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
                        int meshHdrAddr = meshAddr + meshIdx * meshHeaderSize;
                        if (meshHdrAddr + meshHeaderSize > data.length) break;
                        int numStripGroups = buf.getInt(meshHdrAddr);
                        int sgOffset = buf.getInt(meshHdrAddr + 4);

                        // Validate numStripGroups to detect garbage from mesh header misalignment.
                        // Most real meshes have 1-20 strip groups; values >= 64 or <= 0 indicate
                        // a corrupted read (typically from using the wrong mesh header size).
                        if (numStripGroups <= 0 || numStripGroups > 64) {
                            LOGGER.warn("[VtxParser] Mesh[{}] invalid numStripGroups={} (sgOffset=0x{}); treating as empty",
                                meshIdx, numStripGroups, Integer.toHexString(sgOffset));
                            if (l == 0) {
                                result.meshTriangles.add(new ArrayList<>());
                                totalMeshes++;
                            }
                            continue;
                        }

                        if (sgOffset == 0) {
                            if (l == 0) {
                                if (LOG_VERBOSE) {
                                    LOGGER.info("[VtxParser] BP={} Model={} LOD={} Mesh={}: empty (no strip groups)",
                                        bp, m, l, meshIdx);
                                }
                                result.meshTriangles.add(new ArrayList<>());
                                totalMeshes++;
                            }
                            continue;
                        }
                        int sgAddr = meshHdrAddr + sgOffset;

                        List<VtxTriangle> meshTris = new ArrayList<>();

                        for (int sg = 0; sg < numStripGroups; sg++) {
                            int sgHdrAddr = sgAddr + sg * STRIP_GROUP_HEADER_SIZE;
                            if (sgHdrAddr + STRIP_GROUP_HEADER_SIZE > data.length) break;

                            int numVerts = buf.getInt(sgHdrAddr);
                            int vertOff = buf.getInt(sgHdrAddr + 4);
                            int numIndices = buf.getInt(sgHdrAddr + 8);
                            int idxOff = buf.getInt(sgHdrAddr + 12);
                            int numStrips = buf.getInt(sgHdrAddr + 16);
                            int stripOff = buf.getInt(sgHdrAddr + 20);

                            if (LOG_VERBOSE) {
                                LOGGER.info("[VtxParser]   StripGroup[{}]: numVerts={} vertOff=0x{} numIndices={} idxOff=0x{} numStrips={} stripOff=0x{}",
                                    sg, numVerts, Integer.toHexString(vertOff), numIndices, Integer.toHexString(idxOff),
                                    numStrips, Integer.toHexString(stripOff));
                            }

                            if (numVerts <= 0 || numIndices < 3) continue;
                            if (numVerts > MAX_COUNT || numIndices > MAX_COUNT * 3) {
                                LOGGER.warn("[VtxParser] StripGroup[{}] invalid numVerts={} numIndices={}", sg, numVerts, numIndices);
                                continue;
                            }
                            if (numStrips < 0 || numStrips > 256) {
                                LOGGER.warn("[VtxParser] StripGroup[{}] invalid numStrips={}", sg, numStrips);
                                continue;
                            }

                            // Try both relative-to-sgHdrAddr and absolute file addressing
                            int vertDataAddr = resolveOffset(sgHdrAddr, vertOff, (long) numVerts * vertexStride, data.length);
                            if (vertDataAddr < 0) {
                                continue;
                            }
                            int indexDataAddr = resolveOffset(sgHdrAddr, idxOff, (long) numIndices * 2L, data.length);
                            if (indexDataAddr < 0) {
                                continue;
                            }

                            // Validate buffer access boundaries
                            if (vertDataAddr + (long) numVerts * vertexStride > data.length ||
                                vertDataAddr + 4 > data.length) {
                                LOGGER.warn("[VtxParser] StripGroup vertex buffer access out of bounds at 0x{}", Integer.toHexString(vertDataAddr));
                                continue;
                            }

                            int[] origMeshVertIDs = new int[numVerts];
                            for (int vi = 0; vi < numVerts; vi++) {
                                int vertexOffset = vertDataAddr + vi * vertexStride + 4;
                                if (vertexOffset + 2 > data.length) {
                                    LOGGER.warn("[VtxParser] Vertex {} at offset 0x{} exceeds buffer", vi, Integer.toHexString(vertexOffset));
                                    break;
                                }
                                origMeshVertIDs[vi] = buf.getShort(vertexOffset) & 0xFFFF;
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

                            for (int s = 0; s < numStrips; s++) {
                                int sAddr = stripHeadersAddr + s * STRIP_HEADER_SIZE;
                                if (sAddr + STRIP_HEADER_SIZE > data.length) break;

                                int sNumIndices = buf.getInt(sAddr);
                                int sIndexOffset = buf.getInt(sAddr + 4);
                                int sFlags = buf.get(sAddr + STRIP_FLAGS_OFFSET) & 0xFF;

                                if (sNumIndices < 3) continue;
                                if (sIndexOffset < 0 || sIndexOffset + sNumIndices > maxIndices) {
                                    LOGGER.warn("[VtxParser] Strip[{}] invalid sIndexOffset={} sNumIndices={} maxIndices={}",
                                        s, sIndexOffset, sNumIndices, maxIndices);
                                    continue;
                                }

                                boolean isTriList = (sFlags & 0x01) != 0;

                                if (isTriList) {
                                    // ---------- TRIANGLE LIST ----------
                                    for (int i = 0; i + 2 < sNumIndices; i += 3) {
                                        int ci0 = cacheIndices[sIndexOffset + i];
                                        int ci1 = cacheIndices[sIndexOffset + i + 1];
                                        int ci2 = cacheIndices[sIndexOffset + i + 2];
                                        // Skip restart markers and degenerate triangles
                                        if (ci0 == STRIP_RESTART_INDEX || ci1 == STRIP_RESTART_INDEX || ci2 == STRIP_RESTART_INDEX) continue;
                                        if (ci0 == ci1 || ci1 == ci2 || ci0 == ci2) continue;
                                        if (ci0 >= vvdVertexCount || ci1 >= vvdVertexCount || ci2 >= vvdVertexCount) continue;
                                        // Guard against malformed index buffers: ci is an index into
                                        // origMeshVertIDs (sized numVerts), not the VVD vertex count.
                                        if (ci0 < 0 || ci0 >= numVerts || ci1 < 0 || ci1 >= numVerts || ci2 < 0 || ci2 >= numVerts) continue;
                                        meshTris.add(new VtxTriangle(
                                            origMeshVertIDs[ci0], origMeshVertIDs[ci1], origMeshVertIDs[ci2]));
                                    }
                                } else {
                                    // ---------- TRIANGLE STRIP ----------
                                    // Triangle strips require careful handling:
                                    //   1. Indices are a sliding window of 3, advancing by 1 each step
                                    //   2. Winding order alternates every triangle (preserve front-facing)
                                    //   3. 0xFFFF is a D3D strip restart marker — ends current strip, starts new one
                                    //   4. Skip degenerate triangles (two equal indices) which are sometimes
                                    //      used as "connector" triangles between strip segments
                                    // Without steps 3 and 4, the renderer produces "random diagonal lines"
                                    // across the model surface from impossibly-large triangles spanning
                                    // the restart boundary.
                                    //
                                    // Winding convention: the swap is keyed on a strip-relative
                                    // counter (stripRel) that resets to 0 after a restart marker, NOT on
                                    // the absolute index i. Using absolute i is correct within a single
                                    // continuous strip, but after a strip restart (0xFFFF) the absolute
                                    // index may land on the wrong parity — the new strip's first triangle
                                    // always needs even winding, regardless of where the restart was.
                                    //
                                    // stripRel increments every iteration (including degenerate skips,
                                    // since they are part of the strip's vertex stream), and resets to -1
                                    // on restart (loop increment makes it 0 = even for the new strip).
                                    // This avoids both the desync-from-degenerate-skip bug of a running
                                    // parity and the wrong-parity-after-restart bug of absolute-index.
                                    for (int i = 0, stripRel = 0; i + 2 < sNumIndices; i++, stripRel++) {
                                        int ci0 = cacheIndices[sIndexOffset + i];
                                        int ci1 = cacheIndices[sIndexOffset + i + 1];
                                        int ci2 = cacheIndices[sIndexOffset + i + 2];

                                        // Handle strip restart marker: advance past it and reset the
                                        // strip-relative counter so the new strip starts with even winding.
                                        if (ci0 == STRIP_RESTART_INDEX || ci1 == STRIP_RESTART_INDEX || ci2 == STRIP_RESTART_INDEX) {
                                            int advance = (ci0 == STRIP_RESTART_INDEX) ? 1
                                                        : (ci1 == STRIP_RESTART_INDEX) ? 2 : 3;
                                            i += advance - 1; // -1 because loop increments
                                            stripRel = -1;    // loop increments to 0 → even for new strip
                                            continue;
                                        }

                                        // Skip degenerate triangles (zero-area connectors). stripRel still
                                        // increments (they are part of the strip's vertex stream), preserving
                                        // the alternating parity for subsequent non-degenerate triangles.
                                        if (ci0 == ci1 || ci1 == ci2 || ci0 == ci2) {
                                            continue;
                                        }

                                        // Alternating winding keyed on strip-relative counter:
                                        // even stripRel -> (ci0, ci1, ci2), odd stripRel -> (ci1, ci0, ci2).
                                        int tri0, tri1, tri2;
                                        if ((stripRel & 1) == 0) {
                                            tri0 = ci0; tri1 = ci1;
                                        } else {
                                            tri0 = ci1; tri1 = ci0;
                                        }
                                        tri2 = ci2;

                                        if (tri0 >= vvdVertexCount || tri1 >= vvdVertexCount || tri2 >= vvdVertexCount) continue;
                                        // Guard against malformed index buffers: triN is an index into
                                        // origMeshVertIDs (sized numVerts), not the VVD vertex count.
                                        if (tri0 < 0 || tri0 >= numVerts || tri1 < 0 || tri1 >= numVerts || tri2 < 0 || tri2 >= numVerts) continue;
                                        meshTris.add(new VtxTriangle(
                                            origMeshVertIDs[tri0], origMeshVertIDs[tri1], origMeshVertIDs[tri2]));
                                    }
                                }
                                if (isTriList) triListCount++;
                                else triStripCount++;
                            }

                            if (triListCount == 0 && triStripCount == 0) {
                                if (numStrips == 0) {
                                    // No strip headers defined: treat the entire index buffer as a raw triangle list.
                                    for (int i = 0; i + 2 < maxIndices; i += 3) {
                                        int ci0 = cacheIndices[i];
                                        int ci1 = cacheIndices[i + 1];
                                        int ci2 = cacheIndices[i + 2];
                                        // Skip restart markers and degenerate triangles in raw triangle list fallback too
                                        if (ci0 == STRIP_RESTART_INDEX || ci1 == STRIP_RESTART_INDEX || ci2 == STRIP_RESTART_INDEX) continue;
                                        if (ci0 == ci1 || ci1 == ci2 || ci0 == ci2) continue;
                                        if (ci0 >= vvdVertexCount || ci1 >= vvdVertexCount || ci2 >= vvdVertexCount) continue;
                                        // Guard against malformed index buffers: ci is an index into
                                        // origMeshVertIDs (sized numVerts), not the VVD vertex count.
                                        if (ci0 < 0 || ci0 >= numVerts || ci1 < 0 || ci1 >= numVerts || ci2 < 0 || ci2 >= numVerts) continue;
                                        meshTris.add(new VtxTriangle(
                                            origMeshVertIDs[ci0], origMeshVertIDs[ci1], origMeshVertIDs[ci2]));
                                    }
                                } else {
                                    LOGGER.warn("[VtxParser] StripGroup[{}] has {} strips but none produced triangles; skipping fallback to avoid garbage geometry",
                                        sg, numStrips);
                                }
                            }
                        }

                        if (l == 0) {
                            if (LOG_VERBOSE) {
                                LOGGER.info("[VtxParser] BP={} Model={} LOD={} Mesh={}: {} strip groups, {} triangles",
                                    bp, m, l, meshIdx, numStripGroups, meshTris.size());
                            }
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

    private static int resolveOffset(int baseAddr, int relOffset, long dataSize, int bufferLimit) {
        if (relOffset < 0) return -1;

        // Validate inputs to prevent overflow/underflow
        if (baseAddr < 0 || dataSize <= 0 || dataSize > Integer.MAX_VALUE) return -1;

        // Try relative to base address first (standard Source VTX convention)
        int relative = baseAddr + relOffset;
        if (relative >= 0 && (long) relative + dataSize <= bufferLimit) {
            return relative;
        }

        // Fallback: try absolute file offset
        if ((long) relOffset + dataSize <= bufferLimit) {
            if (LOG_VERBOSE) {
                LOGGER.info("[VtxParser] Using absolute offset 0x{} instead of relative (base=0x{})", Integer.toHexString(relOffset), Integer.toHexString(baseAddr));
            }
            return relOffset;
        }
        return -1;
    }
}
