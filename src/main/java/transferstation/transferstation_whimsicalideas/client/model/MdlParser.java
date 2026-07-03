package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MdlParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BODYPARTS = 256;
    private static final int MAX_MODELS = 1024;
    private static final int MAX_MESHES = 4096;
    private static final int MAX_VERTICES = 1_000_000;
    private static final int MAX_FILE_SIZE = 512 * 1024 * 1024;
    private static final int VERTEX_SIZE = 48;
    private static final int BODYPART_SIZE = 16;
    private static final int MODEL_SIZE = 148;
    private static final int MESH_SIZE = 116;
    private static final int BONE_SIZE = 216;
    private static final int EYEBALL_SIZE = 324;
    private static final int TEXTURE_ENTRY_SIZE_V44 = 64;
    private static final int TEXTURE_ENTRY_SIZE_V48 = 52;

    public static class StudioHeader {
        public int id;
        public int version;
        public int checksum;
        public String name;
        public int dataLength;
        public float[] eyeposition;
        public float[] illumposition;
        public float[] hull_min;
        public float[] hull_max;
        public float[] view_bbmin;
        public float[] view_bbmax;
        public int flags;
        public int numbones;
        public int boneindex;
        public int numbonecontrollers;
        public int bonecontrollerindex;
        public int numhitboxsets;
        public int hitboxsetindex;
        public int numlocalanim;
        public int localanimindex;
        public int numlocalseq;
        public int localseqindex;
        public int activitylistversion;
        public int eventsindexed;
        public int numtextures;
        public int textureindex;
        public int numcdtextures;
        public int cdtextureindex;
        public int numskinref;
        public int numskinfamilies;
        public int skinindex;
        public int numbodyparts;
        public int bodypartindex;
        public int numlocalattachments;
        public int localattachmentindex;
        public int numlocalnodes;
        public int localnodeindex;
        public int localnodenameindex;
        public int numflexdesc;
        public int flexdescindex;
        public int numflexcontrollers;
        public int flexcontrollerindex;
        public int numflexrules;
        public int flexruleindex;
        public int numikchains;
        public int ikchainindex;
        public int nummouths;
        public int mouthindex;
        public int numlocalposeparameters;
        public int localposeparamindex;
        public int surfacepropindex;
        public int keyvalueindex;
        public int keyvaluesize;
        public int numlocalikautoplaylocks;
        public int localikautoplaylockindex;
        public float mass;
        public int contents;
        public int numincludemodels;
        public int includemodelindex;
        public int virtualModel;
        public int szanimblocknameindex;
        public int numanimblocks;
        public int animblockindex;
        public int animblockModel;
        public int bonetablenameindex;
        public int vertexbase;
        public int offsetbase;
        public byte directionaldotproduct;
        public byte rootLod;
        public byte numAllowedRootLods;
        public byte unused;
        public int flexcontrolleruiindex;
        public float vertAnimFixedPointScale;
        public int unused3;
        public int studiohdr2index;
    }

    public static class StudioBodyPart {
        public int sznameindex;
        public int nummodels;
        public int baseIndex;
        public int modelindex;
        public int fileOffset;
        public String name;
    }

    public static class StudioModel {
        public String name;
        public int type;
        public float boundingradius;
        public int nummeshes;
        public int meshindex;
        public int numvertices;
        public int vertexindex;
        public int tangentsindex;
        public int numattachments;
        public int attachmentindex;
        public int numeyeballs;
        public int eyeballindex;
        public int[] unused;
        public int fileOffset;
        public int bodypartIndex;
    }

    public static class StudioMesh {
        public int material;
        public int modelindex;
        public int numvertices;
        public int vertexoffset;
        public int numflexes;
        public int flexindex;
        public int materialtype;
        public int materialparam;
        public int meshid;
        public float[] center;
        public int[] unused;
        public int[] extra;
        public int globalModelIndex;
        public int meshLocalIndex;
    }

    public static class StudioVertex {
        public float x, y, z;
        public float nx, ny, nz;
        public float u, v;
    }

    public static class StudioBone {
        public int sznameindex;
        public String name;
        public int parent;
        public int[] bonecontroller;
        public float[] pos;
        public float[] quat;
        public float[] rot;
        public float[] posscale;
        public float[] rotscale;
        public float[] poseToBone;
        public float[] qAlignment;
        public int flags;
        public int proctype;
        public int procindex;
        public int physicsbone;
        public int surfacepropidx;
        public int contents;
        public int[] unused;

        public float[] getWorldPos() {
            return new float[]{pos[0], pos[1], pos[2]};
        }
    }

    public static class StudioEyeball {
        public int sznameindex;
        public int bone;
        public float[] org;
        public float zoffset;
        public float radius;
        public float[] up;
        public float[] forward;
        public int irisMaterial;
        public int upperFlexDesc;
        public int lowerFlexDesc;
        public int upperTarget;
        public int lowerTarget;
        public int upperLidFlexDesc;
        public int lowerLidFlexDesc;
        public int[] unused;
        public byte[] eyelidFlexDesc;
        public int[] unused2;
    }

    public static class StudioTexture {
        public String name;
        public int flags;
        public int width;
        public int height;
    }

    public static class StudioHdr2 {
        public int numSkins;
        public int skinReplacementIndex;
        public int[] skinReplacementCounts;
        public int numSrcBoneTransforms;
        public int srcBoneTransformIndex;
        public int numFlexControllerUI;
        public int flexControllerUIOffset;
        public int eyeControllerNumHistories;
        public int eyeControllerHistoryOffset;

        public boolean hasData;
    }

    public static class ParsedModel {
        public StudioHeader header;
        public StudioHdr2 hdr2;
        public List<StudioBodyPart> bodyParts = new ArrayList<>();
        public List<StudioModel> models = new ArrayList<>();
        public List<StudioMesh> meshes = new ArrayList<>();
        public List<StudioVertex> vertices = new ArrayList<>();
        public List<Integer> indices = new ArrayList<>();
        public List<StudioBone> bones = new ArrayList<>();
        public List<StudioEyeball> eyeballs = new ArrayList<>();
        public List<Integer> meshTrianglesOffset = new ArrayList<>();
        public List<StudioTexture> textures = new ArrayList<>();
        public List<String> cdTextures = new ArrayList<>();
        public List<Integer> skinTable = new ArrayList<>();
        public int vvdVertexCount;
        public List<List<VtxParser.VtxTriangle>> vtxTriangles = new ArrayList<>();
        public List<String> includeModels = new ArrayList<>();
    }

    public static ParsedModel parse(byte[] data) {
        if (data.length > MAX_FILE_SIZE) {
            throw new RuntimeException("MDL file too large: " + data.length + " bytes (max " + MAX_FILE_SIZE + ")");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int bufferLimit = buf.limit();

        ParsedModel result = new ParsedModel();
        result.header = parseHeader(buf, bufferLimit);

        if (result.header.id != 0x54534449) {
            throw new RuntimeException("Not a valid MDL file (bad magic IDTS)");
        }

        result.hdr2 = parseStudioHdr2(buf, result.header, bufferLimit);

        safeRun(() -> parseBodyParts(buf, result, bufferLimit), "bodyparts");
        safeRun(() -> parseModels(buf, result, bufferLimit), "models");
        safeRun(() -> parseMeshes(buf, result, bufferLimit), "meshes");

        // Skip MDL vertex parsing for VVD-based models (vertices come from .vvd files)
        boolean hasVvdVertexData = result.header.textureindex > 0 && result.header.numbones > 0;
        if (!hasVvdVertexData) {
            for (StudioMesh mesh : result.meshes) {
                try {
                    parseVerticesFromMesh(buf, mesh, result, bufferLimit);
                } catch (Exception e) {
                    LOGGER.debug("[MdlParser] Mesh vertex data not in MDL: {}", e.getMessage());
                }
            }
        }

        safeRun(() -> parseIndices(buf, result), "indices");
        safeRun(() -> parseBones(buf, result, bufferLimit), "bones");
        safeRun(() -> parseEyeballs(buf, result, bufferLimit), "eyeballs");
        safeRun(() -> parseTextures(buf, result, bufferLimit), "textures");
        safeRun(() -> parseCdTextures(buf, result, bufferLimit), "cdtextures");
        safeRun(() -> parseSkinTable(buf, result, bufferLimit), "skintable");
        safeRun(() -> parseIncludeModels(buf, result, bufferLimit), "includemodels");

        return result;
    }

    private static StudioHdr2 parseStudioHdr2(ByteBuffer buf, StudioHeader header, int bufferLimit) {
        StudioHdr2 hdr2 = new StudioHdr2();
        hdr2.hasData = false;

        if (header.studiohdr2index <= 0 || header.studiohdr2index >= bufferLimit - 48) {
            return hdr2;
        }

        try {
            int savedPos = buf.position();
            buf.position(header.studiohdr2index);

            hdr2.numSkins = buf.getShort() & 0xFFFF;
            hdr2.skinReplacementIndex = buf.getShort() & 0xFFFF;
            hdr2.numSrcBoneTransforms = buf.getInt();
            hdr2.srcBoneTransformIndex = buf.getInt();
            hdr2.numFlexControllerUI = buf.getInt();
            hdr2.flexControllerUIOffset = buf.getInt();
            hdr2.eyeControllerNumHistories = buf.getInt();
            hdr2.eyeControllerHistoryOffset = buf.getInt();
            skip(buf, 20);
            hdr2.hasData = true;

            buf.position(savedPos);
        } catch (Exception e) {
            LOGGER.debug("[MdlParser] StudioHDR2 parse error (non-fatal): {}", e.getMessage());
        }

        return hdr2;
    }

    private static void skip(ByteBuffer buf, int count) {
        buf.position(buf.position() + count);
    }

    @FunctionalInterface
    private interface SafeRunnable {
        void run() throws Exception;
    }

    private static void safeRun(SafeRunnable r, String section) {
        try {
            r.run();
        } catch (Exception e) {
            LOGGER.debug("[MdlParser] Non-fatal error parsing {}: {}", section, e.getMessage());
        }
    }

    private static void assertInBounds(int offset, int size, int bufferLimit, String fieldName) {
        if (offset < 0 || offset > bufferLimit - size) {
            long endPos = (long) offset + size;
            throw new RuntimeException(String.format(
                    "MDL parse error: %s at offset %d (size %d) exceeds buffer limit %d (would end at %d)",
                    fieldName, offset, size, bufferLimit, endPos));
        }
    }

    private static int sanitizeCount(int count, int max, String fieldName) {
        if (count < 0 || count > max) {
            throw new RuntimeException(String.format(
                    "MDL parse error: %s count %d exceeds maximum %d", fieldName, count, max));
        }
        return count;
    }

    private static long mulAddSafe(int a, int b, int c) {
        return (long) a + (long) b * (long) c;
    }

    private static StudioHeader parseHeader(ByteBuffer buf, int bufferLimit) {
        assertInBounds(buf.position(), 396, bufferLimit, "header");

        StudioHeader h = new StudioHeader();
        h.id = buf.getInt();
        h.version = buf.getInt();
        h.checksum = buf.getInt();
        h.name = readFixedString(buf, 64);
        h.dataLength = buf.getInt();
        h.eyeposition = readFloat3(buf);
        h.illumposition = readFloat3(buf);
        h.hull_min = readFloat3(buf);
        h.hull_max = readFloat3(buf);
        h.view_bbmin = readFloat3(buf);
        h.view_bbmax = readFloat3(buf);
        h.flags = buf.getInt();
        h.numbones = buf.getInt();
        h.boneindex = buf.getInt();
        h.numbonecontrollers = buf.getInt();
        h.bonecontrollerindex = buf.getInt();
        h.numhitboxsets = buf.getInt();
        h.hitboxsetindex = buf.getInt();
        h.numlocalanim = buf.getInt();
        h.localanimindex = buf.getInt();
        h.numlocalseq = buf.getInt();
        h.localseqindex = buf.getInt();
        h.activitylistversion = buf.getInt();
        h.eventsindexed = buf.getInt();
        h.numtextures = buf.getInt();
        h.textureindex = buf.getInt();
        h.numcdtextures = buf.getInt();
        h.cdtextureindex = buf.getInt();
        h.numskinref = buf.getInt();
        h.numskinfamilies = buf.getInt();
        h.skinindex = buf.getInt();
        h.numbodyparts = buf.getInt();
        h.bodypartindex = buf.getInt();
        h.numlocalattachments = buf.getInt();
        h.localattachmentindex = buf.getInt();
        h.numlocalnodes = buf.getInt();
        h.localnodeindex = buf.getInt();
        h.localnodenameindex = buf.getInt();
        h.numflexdesc = buf.getInt();
        h.flexdescindex = buf.getInt();
        h.numflexcontrollers = buf.getInt();
        h.flexcontrollerindex = buf.getInt();
        h.numflexrules = buf.getInt();
        h.flexruleindex = buf.getInt();
        h.numikchains = buf.getInt();
        h.ikchainindex = buf.getInt();
        h.nummouths = buf.getInt();
        h.mouthindex = buf.getInt();
        h.numlocalposeparameters = buf.getInt();
        h.localposeparamindex = buf.getInt();
        h.surfacepropindex = buf.getInt();
        h.keyvalueindex = buf.getInt();
        h.keyvaluesize = buf.getInt();
        h.numlocalikautoplaylocks = buf.getInt();
        h.localikautoplaylockindex = buf.getInt();
        h.mass = buf.getFloat();
        h.contents = buf.getInt();
        h.numincludemodels = buf.getInt();
        h.includemodelindex = buf.getInt();
        h.virtualModel = buf.getInt();
        h.szanimblocknameindex = buf.getInt();
        h.numanimblocks = buf.getInt();
        h.animblockindex = buf.getInt();
        h.animblockModel = buf.getInt();
        h.bonetablenameindex = buf.getInt();
        h.vertexbase = buf.getInt();
        h.offsetbase = buf.getInt();
        h.directionaldotproduct = buf.get();
        h.rootLod = buf.get();
        h.numAllowedRootLods = buf.get();
        h.unused = buf.get();
        h.flexcontrolleruiindex = buf.getInt();
        h.vertAnimFixedPointScale = buf.getFloat();
        h.unused3 = buf.getInt();
        h.studiohdr2index = buf.getInt();
        return h;
    }

    private static void parseBodyParts(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numBodyParts = sanitizeCount(result.header.numbodyparts, MAX_BODYPARTS, "numbodyparts");
        int offset = result.header.bodypartindex;
        assertInBounds(offset, numBodyParts * BODYPART_SIZE, bufferLimit, "bodypartindex");
        buf.position(offset);

        for (int i = 0; i < numBodyParts; i++) {
            StudioBodyPart bp = new StudioBodyPart();
            bp.fileOffset = result.header.bodypartindex + i * BODYPART_SIZE;
            bp.sznameindex = buf.getInt();
            bp.nummodels = buf.getInt();
            bp.baseIndex = buf.getInt();
            bp.modelindex = buf.getInt();
            if (bp.sznameindex > 0) {
                int absNameOff = bp.fileOffset + bp.sznameindex;
                bp.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                bp.name = "";
            }
            result.bodyParts.add(bp);
        }
    }

    private static void parseModels(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int totalModels = 0;
        for (int bpIdx = 0; bpIdx < result.bodyParts.size(); bpIdx++) {
            StudioBodyPart bp = result.bodyParts.get(bpIdx);
            int numModels = sanitizeCount(bp.nummodels, MAX_MODELS - totalModels, "nummodels");
            int modelAddr = bp.fileOffset + bp.modelindex;
            assertInBounds(modelAddr, numModels * MODEL_SIZE, bufferLimit, "modelindex");
            for (int i = 0; i < numModels; i++) {
                int currentAddr = modelAddr + i * MODEL_SIZE;
                buf.position(currentAddr);
                StudioModel m = new StudioModel();
                m.fileOffset = currentAddr;
                m.bodypartIndex = bpIdx;
                m.name = readFixedString(buf, 64);
                m.type = buf.getInt();
                m.boundingradius = buf.getFloat();
                m.nummeshes = buf.getInt();
                m.meshindex = buf.getInt();
                m.numvertices = buf.getInt();
                m.vertexindex = buf.getInt();
                m.tangentsindex = buf.getInt();
                m.numattachments = buf.getInt();
                m.attachmentindex = buf.getInt();
                m.numeyeballs = buf.getInt();
                m.eyeballindex = buf.getInt();
                m.unused = new int[]{buf.getInt(), buf.getInt()};
                result.models.add(m);
                totalModels++;
            }
        }
    }

    private static void parseMeshes(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        for (int modelIdx = 0; modelIdx < result.models.size(); modelIdx++) {
            StudioModel model = result.models.get(modelIdx);
            int numMeshes = sanitizeCount(model.nummeshes, MAX_MESHES - result.meshes.size(), "nummeshes");
            int meshAddr = model.fileOffset + model.meshindex;
            assertInBounds(meshAddr, numMeshes * MESH_SIZE, bufferLimit, "meshindex");
            buf.position(meshAddr);
            for (int i = 0; i < numMeshes; i++) {
                StudioMesh m = new StudioMesh();
                m.material = buf.getInt();
                m.modelindex = buf.getInt();
                m.numvertices = buf.getInt();
                m.vertexoffset = buf.getInt();
                m.numflexes = buf.getInt();
                m.flexindex = buf.getInt();
                m.materialtype = buf.getInt();
                m.materialparam = buf.getInt();
                m.meshid = buf.getInt();
                m.center = readFloat3(buf);
                m.unused = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
                m.extra = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
                m.globalModelIndex = modelIdx;
                m.meshLocalIndex = i;
                result.meshes.add(m);
            }
        }
    }

    private static void parseVerticesFromMesh(ByteBuffer buf, StudioMesh mesh, ParsedModel result, int bufferLimit) {
        StudioModel model = result.models.get(mesh.globalModelIndex);
        int baseVertexOffset = model.vertexindex;
        int numVertices = sanitizeCount(mesh.numvertices, MAX_VERTICES - result.vertices.size(), "mesh.numvertices");

        for (int i = 0; i < numVertices; i++) {
            long pos = mulAddSafe(baseVertexOffset, mesh.vertexoffset + i, VERTEX_SIZE);
            if (pos < 0 || pos > bufferLimit - VERTEX_SIZE) {
                throw new RuntimeException(String.format(
                        "MDL parse error: vertex[%d] position %d exceeds buffer limit %d", i, pos, bufferLimit));
            }
            buf.position((int) pos);
            StudioVertex v = new StudioVertex();
            v.x = buf.getFloat();
            v.y = buf.getFloat();
            v.z = buf.getFloat();
            v.nx = buf.getFloat();
            v.ny = buf.getFloat();
            v.nz = buf.getFloat();
            v.u = buf.getFloat();
            v.v = buf.getFloat();
            result.vertices.add(v);
        }
    }

    public static void linkVtxTriangles(ParsedModel mdl, VtxParser.ParsedVtx vtx, VvdParser.ParsedVvd vvd) {
        mdl.vvdVertexCount = vvd != null ? vvd.vertices.size() : 0;
        int vvdCount = mdl.vvdVertexCount;
        mdl.indices.clear();

        List<List<VtxParser.VtxTriangle>> triangles = VtxParser.buildTrianglesPerMdlMesh(vtx, mdl, vvdCount);
        mdl.vtxTriangles = triangles;

        for (List<VtxParser.VtxTriangle> meshTris : triangles) {
            for (VtxParser.VtxTriangle tri : meshTris) {
                if (tri.v0 < vvdCount && tri.v1 < vvdCount && tri.v2 < vvdCount) {
                    mdl.indices.add(tri.v0);
                    mdl.indices.add(tri.v1);
                    mdl.indices.add(tri.v2);
                }
            }
        }
    }

    private static void parseIndices(ByteBuffer buf, ParsedModel result) {
        if (!result.vtxTriangles.isEmpty()) {
            return;
        }
        if (result.vertices.size() >= 3) {
            for (int i = 0; i + 2 < result.vertices.size(); i += 3) {
                result.indices.add(i);
                result.indices.add(i + 1);
                result.indices.add(i + 2);
            }
        } else {
            for (StudioModel model : result.models) {
                int numVertices = sanitizeCount(model.numvertices, MAX_VERTICES, "model.numvertices");
                int vertexIndexStart = model.vertexindex / VERTEX_SIZE;
                for (int i = 0; i < numVertices; i += 3) {
                    if (i + 2 < numVertices) {
                        result.indices.add(vertexIndexStart + i);
                        result.indices.add(vertexIndexStart + i + 1);
                        result.indices.add(vertexIndexStart + i + 2);
                    }
                }
            }
        }
    }

    private static void parseBones(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numBones = sanitizeCount(result.header.numbones, 512, "numbones");
        if (numBones == 0) return;

        int boneDataBase = result.header.boneindex;
        assertInBounds(boneDataBase, numBones * BONE_SIZE, bufferLimit, "boneindex");
        buf.position(boneDataBase);

        for (int i = 0; i < numBones; i++) {
            StudioBone b = new StudioBone();
            b.sznameindex = buf.getInt();
            b.parent = buf.getInt();
            b.bonecontroller = new int[6];
            for (int j = 0; j < 6; j++) b.bonecontroller[j] = buf.getInt();
            b.pos = readFloat3(buf);
            b.quat = readFloat4(buf);
            b.rot = readFloat3(buf);
            b.posscale = readFloat3(buf);
            b.rotscale = readFloat3(buf);
            b.poseToBone = readFloat12(buf);
            b.qAlignment = readFloat4(buf);
            b.flags = buf.getInt();
            b.proctype = buf.getInt();
            b.procindex = buf.getInt();
            b.physicsbone = buf.getInt();
            b.surfacepropidx = buf.getInt();
            b.contents = buf.getInt();
            b.unused = new int[8];
            for (int j = 0; j < 8; j++) b.unused[j] = buf.getInt();

            if (b.sznameindex > 0) {
                int absNameOff = boneDataBase + b.sznameindex;
                b.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                b.name = "";
            }

            result.bones.add(b);
        }
    }

    private static void parseEyeballs(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        for (StudioModel model : result.models) {
            if (model.numeyeballs <= 0) continue;

            int offset = model.eyeballindex;
            int count = sanitizeCount(model.numeyeballs, 32, "eyeballs");
            assertInBounds(offset, count * EYEBALL_SIZE, bufferLimit, "eyeballindex");
            buf.position(offset);

            for (int i = 0; i < count; i++) {
                StudioEyeball e = new StudioEyeball();
                e.sznameindex = buf.getInt();
                e.bone = buf.getInt();
                e.org = readFloat3(buf);
                e.zoffset = buf.getFloat();
                e.radius = buf.getFloat();
                e.up = readFloat3(buf);
                e.forward = readFloat3(buf);
                e.irisMaterial = buf.getInt();
                e.upperFlexDesc = buf.getInt();
                e.lowerFlexDesc = buf.getInt();
                e.upperTarget = buf.getInt();
                e.lowerTarget = buf.getInt();
                e.upperLidFlexDesc = buf.getInt();
                e.lowerLidFlexDesc = buf.getInt();
                e.unused = new int[4];
                for (int j = 0; j < 4; j++) e.unused[j] = buf.getInt();
                e.eyelidFlexDesc = new byte[4];
                for (int j = 0; j < 4; j++) e.eyelidFlexDesc[j] = buf.get();
                e.unused2 = new int[28];
                for (int j = 0; j < 28; j++) e.unused2[j] = buf.getInt();
                result.eyeballs.add(e);
            }
        }
    }

    private static void parseTextures(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numTextures = result.header.numtextures;
        int textureIndex = result.header.textureindex;
        if (numTextures <= 0 || textureIndex <= 0) return;
        if (numTextures > 256) return;

        int textureEntrySize = (result.header.version >= 48) ? TEXTURE_ENTRY_SIZE_V48 : TEXTURE_ENTRY_SIZE_V44;
        assertInBounds(textureIndex, numTextures * textureEntrySize, bufferLimit, "textureindex");

        for (int i = 0; i < numTextures; i++) {
            int entryOff = textureIndex + i * textureEntrySize;
            buf.position(entryOff);
            StudioTexture tex = new StudioTexture();
            int nameOff = buf.getInt();
            tex.flags = buf.getInt();
            tex.width = buf.getInt();
            tex.height = buf.getInt();
            int viewportX = buf.getInt();
            int viewportY = buf.getInt();

            if (nameOff > 0) {
                int absNameOff = entryOff + nameOff;
                tex.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                tex.name = "";
            }
            result.textures.add(tex);
        }
    }

    private static void parseCdTextures(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numCd = result.header.numcdtextures;
        int cdIndex = result.header.cdtextureindex;
        if (numCd <= 0 || cdIndex <= 0) return;
        if (numCd > 64) return;

        for (int i = 0; i < numCd; i++) {
            int entryOff = cdIndex + i * 4;
            if (entryOff + 3 >= bufferLimit) break;
            int nameOff = buf.getInt(entryOff);
            if (nameOff > 0) {
                String path = readNullTerminatedString(buf, nameOff, bufferLimit);
                if (!path.isEmpty()) {
                    result.cdTextures.add(path);
                }
            }
        }
    }

    private static void parseIncludeModels(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numIncludes = result.header.numincludemodels;
        int includeIndex = result.header.includemodelindex;
        if (numIncludes <= 0 || includeIndex <= 0) return;
        if (numIncludes > 64) return;

        for (int i = 0; i < numIncludes; i++) {
            int entryOff = includeIndex + i * 4;
            if (entryOff + 3 >= bufferLimit) break;
            int nameOff = buf.getInt(entryOff);
            if (nameOff > 0) {
                int absNameOff = includeIndex + nameOff;
                String path = readNullTerminatedString(buf, absNameOff, bufferLimit);
                if (!path.isEmpty()) {
                    result.includeModels.add(path);
                }
            }
        }
    }

    private static void parseSkinTable(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numSkinRef = result.header.numskinref;
        int numSkinFamilies = result.header.numskinfamilies;
        int skinIndex = result.header.skinindex;
        if (numSkinRef <= 0 || numSkinFamilies <= 0 || skinIndex <= 0) return;
        if (numSkinRef > 512 || numSkinFamilies > 64) return;

        int totalEntries = numSkinRef * numSkinFamilies;
        assertInBounds(skinIndex, totalEntries * 2, bufferLimit, "skinindex");

        for (int i = 0; i < totalEntries; i++) {
            int entryOff = skinIndex + i * 2;
            int texIndex = buf.getShort(entryOff) & 0xFFFF;
            result.skinTable.add(texIndex);
        }
    }

    private static float[] readFloat3(ByteBuffer buf) {
        return new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()};
    }

    private static float[] readFloat4(ByteBuffer buf) {
        return new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat(), buf.getFloat()};
    }

    private static float[] readFloat12(ByteBuffer buf) {
        float[] f = new float[12];
        for (int i = 0; i < 12; i++) f[i] = buf.getFloat();
        return f;
    }

    private static String readFixedString(ByteBuffer buf, int length) {
        byte[] bytes = new byte[length];
        buf.get(bytes);
        int nullTerm = 0;
        while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
        return decodeString(bytes, nullTerm);
    }

    private static String readNullTerminatedString(ByteBuffer buf, int offset, int bufferLimit) {
        if (offset < 0 || offset >= bufferLimit) {
            return "";
        }
        int savedPos = buf.position();
        buf.position(offset);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte b;
        int count = 0;
        while (count < 256 && buf.position() < bufferLimit && (b = buf.get()) != 0) {
            baos.write(b);
            count++;
        }
        buf.position(savedPos);
        return decodeString(baos.toByteArray(), baos.size());
    }

    private static final java.nio.charset.Charset CP932 = java.nio.charset.Charset.forName("Shift_JIS");
    private static final java.nio.charset.Charset CP1252 = java.nio.charset.Charset.forName("Windows-1252");

    private static String decodeString(byte[] bytes, int len) {
        if (len <= 0) return "";

        boolean hasHighBytes = false;
        for (int i = 0; i < len; i++) {
            if ((bytes[i] & 0xFF) > 0x7F) {
                hasHighBytes = true;
                break;
            }
        }
        if (!hasHighBytes) {
            return new String(bytes, 0, len, StandardCharsets.US_ASCII);
        }

        try {
            String cp932 = new String(bytes, 0, len, CP932);
            int readable = 0;
            for (int i = 0; i < cp932.length(); i++) {
                char c = cp932.charAt(i);
                if (Character.isLetterOrDigit(c) || Character.isSpaceChar(c) || c == '/' || c == '_' || c == '-' || c == '.') {
                    readable++;
                }
            }
            if (readable > cp932.length() / 2) {
                return cp932;
            }
        } catch (Exception ignored) {}

        try {
            String cp1252 = new String(bytes, 0, len, CP1252);
            int readable = 0;
            for (int i = 0; i < cp1252.length(); i++) {
                char c = cp1252.charAt(i);
                if (Character.isLetterOrDigit(c) || Character.isSpaceChar(c) || c == '/' || c == '_' || c == '-' || c == '.') {
                    readable++;
                }
            }
            if (readable > cp1252.length() / 2) {
                return cp1252;
            }
        } catch (Exception ignored) {}

        return new String(bytes, 0, len, CP932);
    }
}