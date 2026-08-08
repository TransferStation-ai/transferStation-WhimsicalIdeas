package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static transferstation.transferstation_whimsicalideas.client.model.MdlDataTypes.*;

public class MdlParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BODYPARTS = 256;
    private static final int MAX_MODELS = 1024;
    private static final int MAX_MESHES = 4096;
    private static final int MAX_VERTICES = 1_000_000;
    private static final int MAX_FILE_SIZE = 512 * 1024 * 1024;
    private static final int VERTEX_SIZE = 48;
    private static final int BODYPART_SIZE = 16;
    private static final int MODEL_SIZE_V48 = 136;
    private static final int MODEL_SIZE_V49 = 148;
    private static final int MESH_SIZE_V48 = 108;
    private static final int MESH_SIZE_V49 = 116;
    private static final int BONE_SIZE_V47 = 200;
    private static final int BONE_SIZE_V49 = 216;
    private static final int BONE_SIZE_CSGO = 224;
    private static final int EYEBALL_SIZE = 324;
    private static final int TEXTURE_ENTRY_SIZE = 64;
    private static final int SEQDESC_SIZE_V48 = 200;
    private static final int SEQDESC_SIZE_V49 = 220;
    private static final int SEQDESC_SIZE_L4D2 = 240;

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

        // Version-aware struct sizes for cross-engine compatibility
        int ver = result.header.version;
        if (ver <= 47) {
            result.modelSize = MODEL_SIZE_V48;
            result.meshSize = MESH_SIZE_V48;
            result.boneSize = BONE_SIZE_V47;
            result.seqdescSize = SEQDESC_SIZE_V48;
            LOGGER.debug("[MdlParser] Using struct sizes for MDL v{} (legacy GoldSrc/early Source)", ver);
        } else if (ver <= 49) {
            result.modelSize = MODEL_SIZE_V49;
            result.meshSize = MESH_SIZE_V49;
            result.boneSize = BONE_SIZE_V49;
            result.seqdescSize = SEQDESC_SIZE_V49;
            LOGGER.debug("[MdlParser] Using struct sizes for MDL v{} (standard Source)", ver);
        } else if (ver <= 53) {
            result.modelSize = MODEL_SIZE_V49;
            result.meshSize = MESH_SIZE_V49;
            // GMod/SDK 2013 v51-v53 uses 216-byte bones (same as v49), not CS:GO's 224-byte bones.
            // L4D2 v53 also uses 216-byte bones with 220-byte seqdesc (not 240).
            // CS:GO v50+ uses 224-byte bones but has a different header signature.
            // Since GMod is our primary target, use V49 struct sizes for all v50-v53.
            result.boneSize = BONE_SIZE_V49;
            result.seqdescSize = SEQDESC_SIZE_V49;
            LOGGER.debug("[MdlParser] Using struct sizes for MDL v{} (SDK 2013/GMod v53 struct sizes)", ver);
        } else {
            result.modelSize = MODEL_SIZE_V49;
            result.meshSize = MESH_SIZE_V49;
            result.boneSize = BONE_SIZE_CSGO;
            result.seqdescSize = SEQDESC_SIZE_L4D2;
            LOGGER.warn("[MdlParser] Unknown MDL version {}. Using v53 struct sizes.", ver);
        }

        result.hdr2 = parseHdr2(buf, result.header, bufferLimit, result);

        safeRun(() -> parseBodyParts(buf, result, bufferLimit), "bodyparts");
        safeRun(() -> parseModels(buf, result, bufferLimit), "models");
        safeRun(() -> parseMeshes(buf, result, bufferLimit), "meshes");

        // Skip MDL vertex parsing for VVD-based models (vertices come from .vvd files)
        boolean hasVvdVertexData = result.header.textureindex > 0 && result.header.numbones > 0;
        if (!hasVvdVertexData) {
            for (Mesh mesh : result.meshes) {
                try {
                    parseVerticesFromMesh(buf, mesh, result, bufferLimit);
                } catch (Exception e) {
                    LOGGER.debug("[MdlParser] Mesh vertex data not in MDL: {}", e.getMessage());
                }
            }
        }

        safeRun(() -> parseBones(buf, result, bufferLimit), "bones");
        safeRun(() -> parseEyeballs(buf, result, bufferLimit), "eyeballs");
        safeRun(() -> parseAttachments(buf, result, bufferLimit), "attachments");
        safeRun(() -> parseBoneControllers(buf, result, bufferLimit), "bonecontrollers");
        safeRun(() -> parseHitboxSets(buf, result, bufferLimit), "hitboxsets");
        safeRun(() -> parseSequences(buf, result, bufferLimit), "sequences");
        safeRun(() -> parseIKChains(buf, result, bufferLimit), "ikchains");
        safeRun(() -> parseFlexDescriptors(buf, result, bufferLimit), "flexdescriptors");
        safeRun(() -> parseFlexControllers(buf, result, bufferLimit), "flexcontrollers");
        safeRun(() -> parseFlexRules(buf, result, bufferLimit), "flexrules");
        safeRun(() -> parseTextures(buf, result, bufferLimit), "textures");
        safeRun(() -> parseCdTextures(buf, result, bufferLimit), "cdtextures");
        safeRun(() -> parseSkinTable(buf, result, bufferLimit), "skintable");
        safeRun(() -> parseIncludeModels(buf, result, bufferLimit), "includemodels");

        safeRun(() -> parseLocalAnimations(buf, result, bufferLimit), "localanims");
        safeRun(() -> parseSequenceFrameData(buf, result, bufferLimit), "sequenceframedata");
        safeRun(() -> parsePoseParameters(buf, result, bufferLimit), "poseparameters");
        safeRun(() -> parseLocalNodes(buf, result, bufferLimit), "localnodes");
        safeRun(() -> parseIKAutoplayLocks(buf, result, bufferLimit), "ikautoplaylocks");
        safeRun(() -> parseMouths(buf, result, bufferLimit), "mouths");
        safeRun(() -> parseKeyValues(buf, result, bufferLimit), "keyvalues");
        safeRun(() -> parseSurfaceProp(buf, result, bufferLimit), "surfaceprop");
        safeRun(() -> MdlProceduralBones.ProceduralBonesParser.parse(buf, result, bufferLimit), "proceduralbones");
        safeRun(() -> MdlSequenceData.SequenceDataParser.parseIKRules(buf, result, bufferLimit), "seqikrules");
        safeRun(() -> MdlSequenceData.SequenceDataParser.parseAutolayers(buf, result, bufferLimit), "seqautolayers");
        safeRun(() -> MdlSequenceData.SequenceDataParser.parseActivityModifiers(buf, result, bufferLimit), "seqactivitymodifiers");
        safeRun(() -> MdlSequenceData.SequenceDataParser.parseMovements(buf, result, bufferLimit), "seqmovements");
        safeRun(() -> MdlSequenceData.SequenceDataParser.parseLocalHierarchies(buf, result, bufferLimit), "localhierarchies");
        safeRun(() -> MdlFlexAnimation.parse(buf, result, bufferLimit), "flexanimations");

        return result;
    }

    private static Hdr2 parseHdr2(ByteBuffer buf, Header header, int bufferLimit, ParsedModel result) {
        Hdr2 hdr2 = new Hdr2();
        hdr2.hasData = false;

        if (header.studiohdr2index <= 0 || header.studiohdr2index >= bufferLimit - 48) {
            return hdr2;
        }

        int savedPos = buf.position();
        try {
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

            if (hdr2.numSrcBoneTransforms > 0 && hdr2.srcBoneTransformIndex > 0) {
                int srcBoneBase = header.studiohdr2index + hdr2.srcBoneTransformIndex;
                int numSrcBones = Math.min(hdr2.numSrcBoneTransforms, 512);
                int srcBoneSize = 40;
                if (srcBoneBase + numSrcBones * srcBoneSize <= bufferLimit) {
                    for (int s = 0; s < numSrcBones; s++) {
                        int off = srcBoneBase + s * srcBoneSize;
                        SrcBoneTransform bt = new SrcBoneTransform();
                        bt.pos = new float[]{buf.getFloat(off), buf.getFloat(off + 4), buf.getFloat(off + 8)};
                        bt.quat = new float[]{buf.getFloat(off + 12), buf.getFloat(off + 16), buf.getFloat(off + 20), buf.getFloat(off + 24)};
                        bt.scale = new float[]{buf.getFloat(off + 28), buf.getFloat(off + 32), buf.getFloat(off + 36)};
                        result.srcBoneTransforms.add(bt);
                    }
                }
            }

            if (hdr2.numSkins > 0 && hdr2.skinReplacementIndex > 0) {
                int skIdx = header.studiohdr2index + hdr2.skinReplacementIndex;
                int maxSkins = Math.min(hdr2.numSkins, 128);
                hdr2.skinReplacementCounts = new int[maxSkins];
                int totalReplacements = 0;
                for (int s = 0; s < maxSkins; s++) {
                    hdr2.skinReplacementCounts[s] = buf.getInt(skIdx + s * 4);
                    totalReplacements += hdr2.skinReplacementCounts[s];
                }
                if (totalReplacements > 0 && totalReplacements <= 4096) {
                    hdr2.skinReplacementTables = new int[totalReplacements * 2];
                    int tblOff = skIdx + maxSkins * 4;
                    for (int r = 0; r < totalReplacements; r++) {
                        hdr2.skinReplacementTables[r * 2] = buf.getShort(tblOff + r * 4) & 0xFFFF;
                        hdr2.skinReplacementTables[r * 2 + 1] = buf.getShort(tblOff + r * 4 + 2) & 0xFFFF;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[MdlParser] HDR2 parse error (non-fatal): {}", e.getMessage());
        } finally {
            buf.position(savedPos);
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
            if (transferstation.transferstation_whimsicalideas.DebugConfig.isStrictParsing()) {
                throw new RuntimeException("[MdlParser] Strict mode: fatal error parsing " + section, e);
            }
            LOGGER.debug("[MdlParser] Non-fatal error parsing {}: {}", section, e.getMessage());
        }
    }

    private static void assertInBounds(int offset, int size, int bufferLimit, String fieldName) {
        if (offset < 0 || size < 0 || (long) offset + size > bufferLimit) {
            long endPos = (long) offset + size;
            throw new RuntimeException(String.format(
                    "MDL parse error: %s at offset %d (size %d) exceeds buffer limit %d (would end at %d)",
                    fieldName, offset, size, bufferLimit, endPos));
        }
    }

    private static void assertInBounds(int offset, long size, int bufferLimit, String fieldName) {
        if (offset < 0 || size < 0 || (long) offset + size > bufferLimit) {
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

    private static Header parseHeader(ByteBuffer buf, int bufferLimit) {
        assertInBounds(buf.position(), 396, bufferLimit, "header");

        Header h = new Header();
        h.id = buf.getInt();
        h.version = buf.getInt();
        h.checksum = buf.getInt();
        h.name = readFixedString(buf);
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
            BodyPart bp = new BodyPart();
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
        int modelSize = result.modelSize;
        for (int bpIdx = 0; bpIdx < result.bodyParts.size(); bpIdx++) {
            BodyPart bp = result.bodyParts.get(bpIdx);
            int numModels = sanitizeCount(bp.nummodels, MAX_MODELS - totalModels, "nummodels");
            int modelAddr = bp.fileOffset + bp.modelindex;
            assertInBounds(modelAddr, (long) numModels * modelSize, bufferLimit, "modelindex");
            for (int i = 0; i < numModels; i++) {
                int currentAddr = modelAddr + i * modelSize;
                buf.position(currentAddr);
                Model m = new Model();
                m.fileOffset = currentAddr;
                m.bodypartIndex = bpIdx;
                m.name = readFixedString(buf);
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
        int meshSize = result.meshSize;
        for (int modelIdx = 0; modelIdx < result.models.size(); modelIdx++) {
            Model model = result.models.get(modelIdx);
            int numMeshes = sanitizeCount(model.nummeshes, MAX_MESHES - result.meshes.size(), "nummeshes");
            int meshAddr = model.fileOffset + model.meshindex;
            assertInBounds(meshAddr, (long) numMeshes * meshSize, bufferLimit, "meshindex");
            buf.position(meshAddr);
            for (int i = 0; i < numMeshes; i++) {
                Mesh m = new Mesh();
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
                if (meshSize >= MESH_SIZE_V49) {
                    m.unused = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
                    m.extra = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
                } else {
                    m.unused = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
                    m.extra = new int[0];
                }
                m.globalModelIndex = modelIdx;
                m.meshLocalIndex = i;
                result.meshes.add(m);
            }
        }
    }

    private static void parseVerticesFromMesh(ByteBuffer buf, Mesh mesh, ParsedModel result, int bufferLimit) {
        Model model = result.models.get(mesh.globalModelIndex);
        int baseVertexOffset = model.vertexindex;
        int meshOffset = mesh.vertexoffset;
        int numVertices = sanitizeCount(mesh.numvertices, MAX_VERTICES - result.vertices.size(), "mesh.numvertices");

        for (int i = 0; i < numVertices; i++) {
            long pos = (long) baseVertexOffset + (long) meshOffset + (long) i * VERTEX_SIZE;
            if (pos < 0 || pos + VERTEX_SIZE > bufferLimit) {
                throw new RuntimeException(String.format(
                        "MDL parse error: vertex[%d] position %d exceeds buffer limit %d", i, pos, bufferLimit));
            }
            buf.position((int) pos);
            Vertex v = new Vertex();
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

    private static void parseBones(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numBones = sanitizeCount(result.header.numbones, 512, "numbones");
        if (numBones == 0) return;

        int boneSize = result.boneSize;
        int boneDataBase = result.header.boneindex;
        assertInBounds(boneDataBase, (long) numBones * boneSize, bufferLimit, "boneindex");
        buf.position(boneDataBase);

        for (int i = 0; i < numBones; i++) {
            int boneOff = boneDataBase + i * boneSize;
            buf.position(boneOff);
            Bone b = new Bone();
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
            if (boneSize >= BONE_SIZE_V49) {
                b.unused = new int[8];
                for (int j = 0; j < 8; j++) b.unused[j] = buf.getInt();
            } else {
                b.unused = new int[4];
                for (int j = 0; j < 4; j++) b.unused[j] = buf.getInt();
            }

            if (b.sznameindex > 0) {
                // CRITICAL FIX: sznameindex is relative to the BONE ENTRY, not the bone array base.
                // Using boneDataBase would read the wrong name for all bones except bone 0.
                int absNameOff = boneOff + b.sznameindex;
                b.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                b.name = "";
            }

            result.bones.add(b);
        }
        computeWorldInverseBindPose(result.bones, result.invBindPose);
    }

    private static void parseEyeballs(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        for (Model model : result.models) {
            if (model.numeyeballs <= 0) continue;

            int offset = model.eyeballindex;
            int count = sanitizeCount(model.numeyeballs, 32, "eyeballs");
            assertInBounds(offset, count * EYEBALL_SIZE, bufferLimit, "eyeballindex");
            buf.position(offset);

            for (int i = 0; i < count; i++) {
                Eyeball e = new Eyeball();
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

        int textureEntrySize = TEXTURE_ENTRY_SIZE;
        assertInBounds(textureIndex, numTextures * textureEntrySize, bufferLimit, "textureindex");

        for (int i = 0; i < numTextures; i++) {
            int entryOff = textureIndex + i * textureEntrySize;
            buf.position(entryOff);
            Texture tex = new Texture();
            int nameOff = buf.getInt();
            tex.flags = buf.getInt();
            tex.width = buf.getInt();
            tex.height = buf.getInt();
            buf.getInt();
            buf.getInt();

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
                // cdtexture nameOff convention varies by compiler:
                // - studiomdl / standard SDK: absolute offset
                // - Crowbar / some decompilers: relative offset from cdtexture array base
                // - Some community tools: relative offset from entry itself
                // Heuristic: small nameOff (< 256 or < cdIndex) is likely relative;
                // large nameOff is likely absolute.
                boolean likelyRelative = nameOff < 256 || nameOff < cdIndex;
                String path;
                if (likelyRelative) {
                    path = readNullTerminatedString(buf, cdIndex + nameOff, bufferLimit);
                    if (path.isEmpty()) {
                        path = readNullTerminatedString(buf, nameOff, bufferLimit);
                    }
                } else {
                    path = readNullTerminatedString(buf, nameOff, bufferLimit);
                    if (path.isEmpty() && nameOff < bufferLimit) {
                        path = readNullTerminatedString(buf, cdIndex + nameOff, bufferLimit);
                    }
                }
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
                // Try absolute offset first, then fall back to relative
                String path = readNullTerminatedString(buf, nameOff, bufferLimit);
                if (path.isEmpty() && nameOff < bufferLimit) {
                    path = readNullTerminatedString(buf, includeIndex + nameOff, bufferLimit);
                }
                if (!path.isEmpty() && isValidPathString(path)) {
                    result.includeModels.add(path);
                } else if (!path.isEmpty()) {
                    LOGGER.warn("[MdlParser] Skipping include model with invalid path characters: {} (raw bytes)", path);
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

    private static void parseAttachments(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numAttachments = result.header.numlocalattachments;
        int attachmentIndex = result.header.localattachmentindex;
        if (numAttachments <= 0 || attachmentIndex <= 0) return;
        if (numAttachments > 128) return;

        int ATTACHMENT_SIZE = 120;
        assertInBounds(attachmentIndex, numAttachments * ATTACHMENT_SIZE, bufferLimit, "attachmentindex");

        for (int i = 0; i < numAttachments; i++) {
            int entryOff = attachmentIndex + i * ATTACHMENT_SIZE;
            buf.position(entryOff);
            Attachment a = new Attachment();
            a.fileOffset = entryOff;
            a.sznameindex = buf.getInt();
            a.flags = buf.getInt();
            a.attachmentbone = buf.getInt();
            a.org = readFloat3(buf);
            a.vectors = new float[9];
            for (int j = 0; j < 9; j++) a.vectors[j] = buf.getFloat();
            a.quat = readFloat4(buf);
            a.rot = readFloat3(buf);
            skip(buf, 4);
            if (a.sznameindex > 0) {
                int absNameOff = entryOff + a.sznameindex;
                a.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                a.name = "";
            }
            result.attachments.add(a);
        }
    }

    private static void parseBoneControllers(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numBoneControllers = result.header.numbonecontrollers;
        int boneControllerIndex = result.header.bonecontrollerindex;
        if (numBoneControllers <= 0 || boneControllerIndex <= 0) return;
        if (numBoneControllers > 64) return;

        int BONECONTROLLER_SIZE = 28;
        assertInBounds(boneControllerIndex, numBoneControllers * BONECONTROLLER_SIZE, bufferLimit, "bonecontrollerindex");
        buf.position(boneControllerIndex);

        for (int i = 0; i < numBoneControllers; i++) {
            BoneController bc = new BoneController();
            bc.bone = buf.getInt();
            bc.channel = buf.getInt();
            bc.flags = buf.getInt();
            bc.start = readFloat3(buf);
            bc.end = readFloat3(buf);
            bc.rest = buf.getInt();
            bc.inputField = buf.getInt();
            result.boneControllers.add(bc);
        }
    }

    private static void parseHitboxSets(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numHitboxSets = result.header.numhitboxsets;
        int hitboxSetIndex = result.header.hitboxsetindex;
        if (numHitboxSets <= 0 || hitboxSetIndex <= 0) return;
        if (numHitboxSets > 64) return;

        int HITBOXSET_SIZE = 12;
        int HITBOX_SIZE = 44;
        assertInBounds(hitboxSetIndex, numHitboxSets * HITBOXSET_SIZE, bufferLimit, "hitboxsetindex");

        for (int i = 0; i < numHitboxSets; i++) {
            int entryOff = hitboxSetIndex + i * HITBOXSET_SIZE;
            buf.position(entryOff);
            HitboxSet hs = new HitboxSet();
            hs.sznameindex = buf.getInt();
            hs.numhitboxes = buf.getInt();
            hs.hitboxindex = buf.getInt();
            if (hs.sznameindex > 0) {
                // CRITICAL FIX: sznameindex is relative to the HITBOXSET ENTRY, not the array base.
                int absNameOff = entryOff + hs.sznameindex;
                hs.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                hs.name = "";
            }
            int hitboxDataAddr = entryOff + hs.hitboxindex;
            if (hs.numhitboxes > 0 && hs.numhitboxes <= 256) {
                assertInBounds(hitboxDataAddr, hs.numhitboxes * HITBOX_SIZE, bufferLimit, "hitbox");
                for (int h = 0; h < hs.numhitboxes; h++) {
                    int hOff = hitboxDataAddr + h * HITBOX_SIZE;
                    buf.position(hOff);
                    Bbox bbox = new Bbox();
                    bbox.bone = buf.getInt();
                    bbox.group = buf.getInt();
                    bbox.bbmin = readFloat3(buf);
                    bbox.bbmax = readFloat3(buf);
                    bbox.sznameindex = buf.getInt();
                    for (int j = 0; j < 8; j++) buf.getInt();
                    if (bbox.sznameindex > 0) {
                        int absNameOff = hOff + bbox.sznameindex;
                        bbox.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
                    } else {
                        bbox.name = "";
                    }
                    hs.hitboxes.add(bbox);
                }
            }
            result.hitboxSets.add(hs);
        }
    }

    private static void parseSequences(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numLocalSeq = result.header.numlocalseq;
        int localSeqIndex = result.header.localseqindex;
        if (numLocalSeq <= 0 || localSeqIndex <= 0) return;
        if (numLocalSeq > 1024) return;

        int seqdescSize = result.seqdescSize;
        assertInBounds(localSeqIndex, (long) numLocalSeq * seqdescSize, bufferLimit, "localseqindex");

        for (int i = 0; i < numLocalSeq; i++) {
            int entryOff = localSeqIndex + i * seqdescSize;
            buf.position(entryOff);
            SeqDesc seq = new SeqDesc();
            seq.baseptr = buf.getInt();
            seq.sznameindex = buf.getInt();
            if (seq.sznameindex > 0) {
                int absNameOff = entryOff + seq.sznameindex;
                seq.label = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                seq.label = "";
            }
            if (seqdescSize >= SEQDESC_SIZE_V49) {
                seq.szactivitynameindex = buf.getInt();
                buf.getInt();
            } else {
                seq.szactivitynameindex = 0;
            }
            seq.activity = buf.getInt();
            seq.actweight = buf.getInt();
            seq.events = new int[]{buf.getInt(), buf.getInt()};
            seq.numevents = buf.getInt();
            seq.eventindex = buf.getInt();
            seq.numframes = buf.getInt();
            seq.numpivots = buf.getInt();
            seq.pivotindex = buf.getInt();
            seq.motiontype = buf.getInt();
            seq.motionbone = buf.getInt();
            seq.linearmovement = readFloat3(buf);
            seq.automoveposindex = buf.getInt();
            seq.bbmin = readFloat3(buf);
            seq.bbmax = readFloat3(buf);
            seq.numblends = buf.getInt();
            seq.animindex = buf.getInt();
            seq.blend = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
            seq.blendpos = readFloat3(buf);
            seq.numlocalhints = buf.getInt();
            seq.localhintindex = buf.getInt();
            seq.groupSize = buf.getInt();
            seq.numIK = buf.getInt();
            seq.IKIndex = buf.getInt();
            seq.flags = buf.getInt();
            seq.fadeInTime = new float[]{buf.getFloat(), buf.getFloat()};
            seq.fadeOutTime = new float[]{buf.getFloat(), buf.getFloat()};
            seq.localEntryNode = buf.getInt();
            seq.localExitNode = buf.getInt();
            seq.nodeFlags = buf.getInt();
            seq.entryPhase = buf.getFloat();
            seq.exitPhase = buf.getFloat();
            seq.lastFrame = buf.getFloat();
            seq.nextSeq = buf.getInt();
            seq.pose = buf.getInt();
            seq.poseKey = new float[]{buf.getFloat(), buf.getFloat()};
            seq.keyValueIndex = new float[]{buf.getFloat(), buf.getFloat()};
            seq.keyValueSize = buf.getInt();
            seq.paramValue = buf.getInt();
            result.sequences.add(seq);

            // Classify sequence
            SequenceType stype = classifySequence(seq);
            if (stype == SequenceType.REFERENCE) {
                result.referenceSequenceIndices.add(i);
            } else if (stype == SequenceType.A_POSE) {
                result.aPoseSequenceIndices.add(i);
            }
        }
    }

    private static void parseIKChains(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numIKChains = result.header.numikchains;
        int ikChainIndex = result.header.ikchainindex;
        if (numIKChains <= 0 || ikChainIndex <= 0) return;
        if (numIKChains > 32) return;

        int IKCHAIN_SIZE = 20;
        int IKLINK_SIZE = 24;
        assertInBounds(ikChainIndex, numIKChains * IKCHAIN_SIZE, bufferLimit, "ikchainindex");

        for (int i = 0; i < numIKChains; i++) {
            int entryOff = ikChainIndex + i * IKCHAIN_SIZE;
            buf.position(entryOff);
            IKChain ik = new IKChain();
            ik.fileOffset = entryOff;
            ik.sznameindex = buf.getInt();
            ik.chain = buf.getInt();
            ik.numlinks = buf.getInt();
            ik.linkindex = buf.getInt();
            int linkDataAddr = entryOff + ik.linkindex;
            int numLinks = Math.min(ik.numlinks, 16);
            for (int l = 0; l < numLinks; l++) {
                int lOff = linkDataAddr + l * IKLINK_SIZE;
                buf.position(lOff);
                IKLink link = new IKLink();
                link.bone = buf.getInt();
                link.kneeDir = readFloat3(buf);
                link.limits = readFloat3(buf);
                link.unused = buf.getInt();
                ik.links.add(link);
            }
            if (ik.sznameindex > 0) {
                int absNameOff = entryOff + ik.sznameindex;
                ik.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                ik.name = "";
            }
            result.ikChains.add(ik);
        }
    }

    private static void parseFlexDescriptors(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numFlexDesc = result.header.numflexdesc;
        int flexDescIndex = result.header.flexdescindex;
        if (numFlexDesc <= 0 || flexDescIndex <= 0) return;
        if (numFlexDesc > 256) return;

        int FLEXDESC_SIZE = 8;
        assertInBounds(flexDescIndex, numFlexDesc * FLEXDESC_SIZE, bufferLimit, "flexdescindex");

        for (int i = 0; i < numFlexDesc; i++) {
            int entryOff = flexDescIndex + i * FLEXDESC_SIZE;
            buf.position(entryOff);
            FlexDesc fd = new FlexDesc();
            fd.sznameindex = buf.getInt();
            fd.name = "";
            if (fd.sznameindex > 0) {
                int absNameOff = entryOff + fd.sznameindex;
                fd.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            }
            result.flexDescs.add(fd);
        }
    }

    private static void parseFlexControllers(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numFlexControllers = result.header.numflexcontrollers;
        int flexControllerIndex = result.header.flexcontrollerindex;
        if (numFlexControllers <= 0 || flexControllerIndex <= 0) return;
        if (numFlexControllers > 64) return;

        int FLEXCONTROLLER_SIZE = 32;
        assertInBounds(flexControllerIndex, numFlexControllers * FLEXCONTROLLER_SIZE, bufferLimit, "flexcontrollerindex");
        buf.position(flexControllerIndex);

        for (int i = 0; i < numFlexControllers; i++) {
            int entryOff = flexControllerIndex + i * FLEXCONTROLLER_SIZE;
            buf.position(entryOff);
            FlexController fc = new FlexController();
            fc.sznameindex = buf.getInt();
            fc.name = "";
            fc.localToGlobal = new int[]{buf.getInt(), buf.getInt()};
            fc.min = readFloat3(buf);
            fc.max = readFloat3(buf);
            if (fc.sznameindex > 0) {
                int absNameOff = entryOff + fc.sznameindex;
                fc.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            }
            result.flexControllers.add(fc);
        }
    }

    private static void parseFlexRules(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numFlexRules = result.header.numflexrules;
        int flexRuleIndex = result.header.flexruleindex;
        if (numFlexRules <= 0 || flexRuleIndex <= 0) return;
        if (numFlexRules > 256) return;

        int FLEXRULE_SIZE = 12;
        assertInBounds(flexRuleIndex, numFlexRules * FLEXRULE_SIZE, bufferLimit, "flexruleindex");

        for (int i = 0; i < numFlexRules; i++) {
            int entryOff = flexRuleIndex + i * FLEXRULE_SIZE;
            buf.position(entryOff);
            FlexRule fr = new FlexRule();
            fr.flex = buf.getInt();
            fr.numops = buf.getInt();
            fr.opindex = buf.getInt();
            int opDataAddr = entryOff + fr.opindex;
            int numOps = Math.min(fr.numops, 1024);
            fr.ops = new int[numOps];
            for (int o = 0; o < numOps; o++) {
                fr.ops[o] = buf.getInt(opDataAddr + o * 4);
            }
            result.flexRules.add(fr);
        }
    }

    private static void parseLocalAnimations(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numAnims = result.header.numlocalanim;
        int animIndex = result.header.localanimindex;
        if (numAnims <= 0 || animIndex <= 0) return;
        if (numAnims > 256) return;

        int LOCALANIM_SIZE = 64;
        assertInBounds(animIndex, numAnims * LOCALANIM_SIZE, bufferLimit, "localanimindex");

        for (int i = 0; i < numAnims; i++) {
            int entryOff = animIndex + i * LOCALANIM_SIZE;
            buf.position(entryOff);

            LocalAnim anim = new LocalAnim();
            int nameOff = buf.getInt();
            anim.animBlock = buf.getInt();
            anim.animOffset = buf.getInt();
            anim.numFrames = buf.getInt();
            anim.numSegments = buf.getInt();
            anim.segmentIndex = buf.getInt();
            anim.flags = buf.getInt();
            anim.fps = buf.getFloat();
            anim.animBlocks = new int[]{buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt()};
            skip(buf, 12);

            if (nameOff > 0) {
                int absNameOff = entryOff + nameOff;
                anim.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                anim.name = "";
            }
            result.localAnims.add(anim);
        }
    }

    private static void parsePoseParameters(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numParams = result.header.numlocalposeparameters;
        int paramIndex = result.header.localposeparamindex;
        if (numParams <= 0 || paramIndex <= 0) return;
        if (numParams > 64) return;

        int POSEPARAM_SIZE = 24;
        assertInBounds(paramIndex, numParams * POSEPARAM_SIZE, bufferLimit, "localposeparamindex");
        buf.position(paramIndex);

        for (int i = 0; i < numParams; i++) {
            int entryOff = paramIndex + i * POSEPARAM_SIZE;
            buf.position(entryOff);
            PoseParam pp = new PoseParam();
            int nameOff = buf.getInt();
            pp.type = buf.getInt();
            pp.start = buf.getFloat();
            pp.end = buf.getFloat();
            pp.loop = buf.getInt();
            skip(buf, 4);

            if (nameOff > 0) {
                int absNameOff = entryOff + nameOff;
                pp.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                pp.name = "";
            }
            result.poseParams.add(pp);
        }
    }

    private static void parseLocalNodes(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numNodes = result.header.numlocalnodes;
        int nodeIndex = result.header.localnodeindex;
        int nodeNameIndex = result.header.localnodenameindex;
        if (numNodes <= 0 || nodeIndex <= 0) return;
        if (numNodes > 256) return;

        int LOCALNODE_SIZE = 8;
        assertInBounds(nodeIndex, numNodes * LOCALNODE_SIZE, bufferLimit, "localnodeindex");

        for (int i = 0; i < numNodes; i++) {
            int entryOff = nodeIndex + i * LOCALNODE_SIZE;
            buf.position(entryOff);

            LocalNode node = new LocalNode();
            int nameOff = buf.getInt();
            node.parent = buf.getInt();

            if (nameOff > 0 && nodeNameIndex > 0) {
                int absNameOff = nodeNameIndex + nameOff;
                node.name = readNullTerminatedString(buf, absNameOff, bufferLimit);
            } else {
                node.name = "";
            }
            result.localNodes.add(node);
        }
    }

    private static void parseIKAutoplayLocks(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numLocks = result.header.numlocalikautoplaylocks;
        int lockIndex = result.header.localikautoplaylockindex;
        if (numLocks <= 0 || lockIndex <= 0) return;
        if (numLocks > 32) return;

        int IKAUTOPLAYLOCK_SIZE = 12;
        assertInBounds(lockIndex, numLocks * IKAUTOPLAYLOCK_SIZE, bufferLimit, "localikautoplaylockindex");
        buf.position(lockIndex);

        for (int i = 0; i < numLocks; i++) {
            IKAutoplayLock lock = new IKAutoplayLock();
            lock.ikChainIndex = buf.getInt();
            lock.lockCount = buf.getInt();
            lock.threshold = buf.getFloat();
            result.ikAutoplayLocks.add(lock);
        }
    }

    private static void parseMouths(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numMouths = result.header.nummouths;
        int mouthIndex = result.header.mouthindex;
        if (numMouths <= 0 || mouthIndex <= 0) return;
        if (numMouths > 64) return;

        int MOUTH_SIZE = 16;
        assertInBounds(mouthIndex, numMouths * MOUTH_SIZE, bufferLimit, "mouthindex");
        buf.position(mouthIndex);

        for (int i = 0; i < numMouths; i++) {
            Mouth mouth = new Mouth();
            mouth.bone = buf.getInt();
            mouth.flexibleOffsets = new float[]{buf.getFloat(), buf.getFloat(), buf.getFloat()};
            result.mouths.add(mouth);
        }
    }

    private static void parseKeyValues(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int kvIndex = result.header.keyvalueindex;
        int kvSize = result.header.keyvaluesize;
        if (kvIndex <= 0 || kvSize <= 0) return;
        if (kvSize > 65536) return;

        assertInBounds(kvIndex, kvSize, bufferLimit, "keyvalueindex");
        result.keyValues = readFixedLengthString(buf, kvIndex, kvSize, bufferLimit);
    }

    private static String readFixedLengthString(ByteBuffer buf, int offset, int length, int bufferLimit) {
        if (offset < 0 || length <= 0 || offset > bufferLimit - length) return "";
        int savedPos = buf.position();
        try {
            buf.position(offset);
            byte[] bytes = new byte[Math.min(length, bufferLimit - offset)];
            buf.get(bytes);
            int nullTerm = 0;
            while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
            return decodeString(bytes, nullTerm);
        } finally {
            buf.position(savedPos);
        }
    }

    private static void parseSurfaceProp(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int spIndex = result.header.surfacepropindex;
        if (spIndex <= 0) return;
        result.surfaceProp = readNullTerminatedString(buf, spIndex, bufferLimit);
    }

    private static void parseSequenceFrameData(ByteBuffer buf, ParsedModel result, int bufferLimit) {
        int numAnims = result.localAnims.size();
        int numSeqs = result.sequences.size();
        if (numAnims == 0 && numSeqs == 0) return;

        // Build mapping: for each sequence, try to find the corresponding local animation
        // and extract frame 0 bone transforms
        int localAnimBase = result.header.localanimindex;
        if (localAnimBase <= 0) return;

        result.sequenceAnimData.clear();
        int localAnimSize = 64; // mstudioanimdesc_t size
        for (int si = 0; si < numSeqs; si++) {
            SeqDesc seq = result.sequences.get(si);
            SequenceAnimData animData = new SequenceAnimData();

            // Determine which local anim this sequence references
            int animIdx = -1;
            if (seq.numblends > 0 && seq.animindex > 0) {
                // CRITICAL FIX: seq.animindex is an offset from the SEQUENCE ENTRY to mstudioanimdesc_t,
                // not an index or offset from localAnimBase.
                // The mstudioanimdesc_t at that address should correspond to one of the entries
                // in the local anim table (at localanimindex).
                int seqEntryOff = result.header.localseqindex + si * result.seqdescSize;
                int animDescOff = seqEntryOff + seq.animindex;
                if (animDescOff >= localAnimBase && animDescOff < localAnimBase + numAnims * localAnimSize) {
                    // The animdesc is within the local anim table — compute its index
                    if ((animDescOff - localAnimBase) % localAnimSize == 0) {
                        animIdx = (animDescOff - localAnimBase) / localAnimSize;
                    }
                }
                // If animIdx is still < 0, try reading baseptr from mstudioanimdesc_t
                if (animIdx < 0 && animDescOff + 8 <= bufferLimit) {
                    int baseptr = buf.getInt(animDescOff);
                    // baseptr might be an index into the local anim table
                    if (baseptr >= 0 && baseptr < numAnims) {
                        animIdx = baseptr;
                    }
                }
            }
            if (animIdx < 0 || animIdx >= numAnims) {
                result.sequenceAnimData.add(animData);
                continue;
            }

            LocalAnim localAnim = result.localAnims.get(animIdx);
            int segmentOff = localAnimBase + localAnim.segmentIndex;
            if (segmentOff <= 0 || segmentOff >= bufferLimit) {
                result.sequenceAnimData.add(animData);
                continue;
            }

            // Parse frame 0 bone transforms from segment data
            AnimFrameData frame0 = new AnimFrameData();
            frame0.frame = 0;

            int savedPos = buf.position();
            try {
                // Read bone entries from segment data
                // Format: series of mstudioanim_t structs
                // short bone, short flags, int nextoffset, then data
                int entryOff = segmentOff;
                int maxIter = Math.min(result.bones.size(), 256);
                for (int iter = 0; iter < maxIter; iter++) {
                    if (entryOff + 8 > bufferLimit) break;

                    short boneIdx = buf.getShort(entryOff);
                    buf.getShort(entryOff + 2);
                    int nextoffset = buf.getInt(entryOff + 4);

                    if (boneIdx < 0) break;
                    if (boneIdx >= result.bones.size()) {
                        if (nextoffset > 0) {
                            entryOff = segmentOff + nextoffset;
                        } else {
                            entryOff += 8 + 12; // skip header + 6 shorts
                        }
                        continue;
                    }

                    AnimFrameBone fb = new AnimFrameBone();
                    fb.boneIndex = boneIdx;
                    fb.boneName = result.bones.get(boneIdx).name;

                    int dataOff = entryOff + 8;

                    // Read 6 shorts: pos[3] + quat[3] (raw format)
                    if (dataOff + 12 <= bufferLimit) {
                        short px = buf.getShort(dataOff);
                        short py = buf.getShort(dataOff + 2);
                        short pz = buf.getShort(dataOff + 4);
                        short qx = buf.getShort(dataOff + 6);
                        short qy = buf.getShort(dataOff + 8);
                        short qz = buf.getShort(dataOff + 10);

                        // Decode shorts to floats (normalized)
                        float invScale = 1.0f / 32767.0f;
                        fb.pos[0] = px * invScale;
                        fb.pos[1] = py * invScale;
                        fb.pos[2] = pz * invScale;
                        fb.quat[0] = qx * invScale;
                        fb.quat[1] = qy * invScale;
                        fb.quat[2] = qz * invScale;
                        // Compute quat w
                        float qlen = fb.quat[0] * fb.quat[0] + fb.quat[1] * fb.quat[1] + fb.quat[2] * fb.quat[2];
                        fb.quat[3] = (qlen < 1.0f) ? (float) Math.sqrt(1.0f - qlen) : 0;
                    }

                    frame0.boneTransforms.add(fb);

                    if (nextoffset > 0) {
                        entryOff = segmentOff + nextoffset;
                    } else {
                        entryOff = dataOff + 12;
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("[MdlParser] Failed to parse segment data for sequence '{}': {}", seq.label, e.getMessage());
            }

            if (!frame0.boneTransforms.isEmpty()) {
                animData.frames.add(frame0);
                animData.isReference = result.referenceSequenceIndices.contains(si);
                animData.isAPose = result.aPoseSequenceIndices.contains(si);
            }

            buf.position(savedPos);
            result.sequenceAnimData.add(animData);
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

    /**
     * Mirrors the C++ native parser's invBindPose computation (mdl_parser.cpp):
     * invBindPose[i] is the INVERSE of bone i's WORLD bind pose — the pose-to-bone
     * matrices concatenated up the parent chain. Multiplying the animated world
     * bone matrix by this inverse yields the Source-engine skin matrix, so
     * vertices in the rest pose stay put (identity) and only animation deltas
     * deform them.
     */
    private static void computeWorldInverseBindPose(List<Bone> bones, List<float[]> out) {
        int n = bones.size();
        if (n == 0) return;
        float[][] bindWorld = new float[n][16];
        for (int i = 0; i < n; i++) {
            Bone b = bones.get(i);
            int parent = b.parent;
            if (parent >= 0 && parent < n && bindWorld[parent] != null) {
                // world = bindWorld[parent] * local (matches Matrix4x4::multiply)
                bindWorld[i] = mul4x4(bindWorld[parent], from3x4(b.poseToBone));
            } else {
                bindWorld[i] = from3x4(b.poseToBone);
            }
            out.add(inverse4x4(bindWorld[i]));
        }
    }

    /**
     * Matches {@code Matrix4x4::from3x4} in matrix_math.h: the Source row-major
     * 3x4 poseToBone maps into the column-major 4x4 array {@code m[col * 4 + row]}
     * exactly as the C++ code does.
     */
    private static float[] from3x4(float[] src) {
        float[] m = new float[16];
        m[0]  = src[0];
        m[1]  = src[4];
        m[2]  = src[8];
        m[4]  = src[1];
        m[5]  = src[5];
        m[6]  = src[9];
        m[8]  = src[2];
        m[9]  = src[6];
        m[10] = src[10];
        m[12] = src[3];
        m[13] = src[7];
        m[14] = src[11];
        m[15] = 1.0f;
        return m;
    }

    /** Matches {@code Matrix4x4::multiply} (column-major, {@code m[col * 4 + row]}). */
    private static float[] mul4x4(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0.0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = sum;
            }
        }
        return r;
    }

    /** Matches {@code Matrix4x4::inverse} (rigid transform, column-major). */
    private static float[] inverse4x4(float[] m) {
        float[] inv = new float[16];
        inv[0]  = m[0];  inv[1]  = m[4];  inv[2]  = m[8];  inv[3]  = 0.0f;
        inv[4]  = m[1];  inv[5]  = m[5];  inv[6]  = m[9];  inv[7]  = 0.0f;
        inv[8]  = m[2];  inv[9]  = m[6];  inv[10] = m[10]; inv[11] = 0.0f;
        inv[12] = -(m[0] * m[12] + m[1] * m[13] + m[2] * m[14]);
        inv[13] = -(m[4] * m[12] + m[5] * m[13] + m[6] * m[14]);
        inv[14] = -(m[8] * m[12] + m[9] * m[13] + m[10] * m[14]);
        inv[15] = 1.0f;
        return inv;
    }

    private static String readFixedString(ByteBuffer buf) {
        byte[] bytes = new byte[64];
        buf.get(bytes);
        int nullTerm = 0;
        while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
        return decodeString(bytes, nullTerm);
    }

    private static boolean isValidPathString(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Reject only control characters and Windows-prohibited filename characters.
            // Allow extended ASCII (128-255) and Unicode for non-English mod paths.
            if (c < 32) return false;
            if (c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') return false;
            if (c == '\ufffd') return false;
        }
        return true;
    }

    private static String readNullTerminatedString(ByteBuffer buf, int offset, int bufferLimit) {
        if (offset < 0 || offset >= bufferLimit) {
            return "";
        }
        int savedPos = buf.position();
        try {
            buf.position(offset);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            int count = 0;
            while (count < 256 && buf.position() < bufferLimit) {
                byte b = buf.get();
                if (b == 0) break;
                baos.write(b);
                count++;
            }
            return decodeString(baos.toByteArray(), baos.size());
        } finally {
            buf.position(savedPos);
        }
    }

    private static final java.nio.charset.Charset CP932 = java.nio.charset.Charset.forName("Shift_JIS");
    private static final java.nio.charset.Charset CP1252 = java.nio.charset.Charset.forName("Windows-1252");
    private static final java.nio.charset.Charset GBK = java.nio.charset.Charset.forName("GBK");

    private static final java.nio.charset.CharsetDecoder UTF8_STRICT = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);

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

        // Source Engine MDL string tables store text as UTF-8. If the byte sequence is
        // strictly valid UTF-8, always decode as UTF-8 regardless of how the bytes would
        // also decode under GBK/CP932/CP1252 (those multibyte charsets pack UTF-8 bytes
        // into denser CJK chars and would "win" a naive letter-count score, corrupting
        // names). Only fall back to legacy encodings when UTF-8 decoding fails outright.
        String utf8Result;
        try {
            utf8Result = UTF8_STRICT.decode(java.nio.ByteBuffer.wrap(bytes, 0, len)).toString();
            return utf8Result;
        } catch (java.nio.charset.CharacterCodingException e) {
            // Not valid UTF-8; fall through to legacy single-/double-byte encodings.
        }

        String gbkResult = safeDecode(bytes, len, GBK);
        String cp932Result = safeDecode(bytes, len, CP932);
        String cp1252Result = safeDecode(bytes, len, CP1252);

        int bestScore = Math.max(
            gbkResult == null ? Integer.MIN_VALUE : calculateStringScore(gbkResult),
            Math.max(
                cp932Result == null ? Integer.MIN_VALUE : calculateStringScore(cp932Result),
                cp1252Result == null ? Integer.MIN_VALUE : calculateStringScore(cp1252Result)));
        if (bestScore > Integer.MIN_VALUE / 2) {
            if (gbkResult != null && calculateStringScore(gbkResult) == bestScore) return gbkResult;
            if (cp932Result != null && calculateStringScore(cp932Result) == bestScore) return cp932Result;
            return cp1252Result;
        }

        String fallback = pickBestFallback(gbkResult, cp932Result, cp1252Result);
        if (fallback != null) return fallback;

        return new String(bytes, 0, len, StandardCharsets.US_ASCII).replace('?', '_');
    }

    private static String safeDecode(byte[] bytes, int len, java.nio.charset.Charset cs) {
        try {
            return new String(bytes, 0, len, cs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String pickBestFallback(String... candidates) {
        for (String s : candidates) {
            if (s == null) continue;
            if (s.indexOf('\ufffd') >= 0) continue;
            return s;
        }
        return null;
    }

    private static int calculateStringScore(String s) {
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                score += 2;
            } else if (Character.isSpaceChar(c) || c == '/' || c == '_' || c == '-' || c == '.') {
                score += 1;
            } else if (c == '?' || c == '\ufffd') {
                score -= 10; // Strong penalty for replacement characters
            } else if (c < 32 || (c > 126 && c < 160)) {
                score -= 5; // Penalty for control characters
            }
        }
        return score;
    }
}