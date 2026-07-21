package transferstation.transferstation_whimsicalideas.client.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Diagnostic utility that parses Source Engine model files (.mdl, .vvd, .dx90.vtx)
 * and dumps detailed structural information to stdout.
 * <p>
 * Usage:
 *   MdlDiagnostic --mdl &lt;path&gt; --vvd &lt;path&gt; --vtx &lt;path&gt;
 * <p>
 * All arguments are optional. At least one file should be specified.
 */
public class MdlDiagnostic {

    public static void main(String[] args) {
        String mdlPath = null;
        String vvdPath = null;
        String vtxPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mdl":
                    if (i + 1 < args.length) mdlPath = args[++i];
                    break;
                case "--vvd":
                    if (i + 1 < args.length) vvdPath = args[++i];
                    break;
                case "--vtx":
                    if (i + 1 < args.length) vtxPath = args[++i];
                    break;
                default:
                    System.out.println("[MdlDiagnostic] Unknown argument: " + args[i]);
                    System.out.println("Usage: MdlDiagnostic --mdl <path> --vvd <path> --vtx <path>");
                    return;
            }
        }

        if (mdlPath == null && vvdPath == null && vtxPath == null) {
            System.out.println("[MdlDiagnostic] No files specified. Usage: --mdl <path> --vvd <path> --vtx <path>");
            return;
        }

        System.out.println("================================================");
        System.out.println("  Source Model File Diagnostic");
        System.out.println("================================================");

        if (mdlPath != null) {
            System.out.println();
            System.out.println("================================================");
            System.out.println("  MDL File: " + mdlPath);
            System.out.println("================================================");
            dumpMdl(mdlPath);
        }

        if (vvdPath != null) {
            System.out.println();
            System.out.println("================================================");
            System.out.println("  VVD File: " + vvdPath);
            System.out.println("================================================");
            dumpVvd(vvdPath);
        }

        if (vtxPath != null) {
            System.out.println();
            System.out.println("================================================");
            System.out.println("  VTX File: " + vtxPath);
            System.out.println("================================================");
            dumpVtx(vtxPath);
        }

        System.out.println();
        System.out.println("================================================");
        System.out.println("  Diagnostic complete.");
        System.out.println("================================================");
    }

    // ---------------------------------------------------------------
    //  MDL
    // ---------------------------------------------------------------

    private static void dumpMdl(String path) {
        byte[] data = readFile(path);
        if (data == null) return;

        MdlDataTypes.ParsedModel mdl;
        try {
            mdl = MdlParser.parse(data);
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to parse MDL: " + e.getMessage());
            e.printStackTrace(System.out);
            return;
        }

        // --- Header ---
        dumpHeader(mdl.header);

        // --- StudioHdr2 ---
        dumpHdr2(mdl.hdr2);

        // --- Body Parts ---
        System.out.println();
        System.out.println("--- Body Parts (" + mdl.bodyParts.size() + ") ---");
        for (int i = 0; i < mdl.bodyParts.size(); i++) {
            MdlDataTypes.StudioBodyPart bp = mdl.bodyParts.get(i);
            System.out.println("  [" + i + "] name='" + bp.name + "' numModels=" + bp.nummodels);
        }

        // --- Models ---
        System.out.println();
        System.out.println("--- Models (" + mdl.models.size() + ") ---");
        for (int i = 0; i < mdl.models.size(); i++) {
            MdlDataTypes.StudioModel m = mdl.models.get(i);
            System.out.println("  [" + i + "] name='" + m.name + "' bodyPart=" + m.bodypartIndex
                + " type=" + m.type + " radius=" + m.boundingradius
                + " numMeshes=" + m.nummeshes + " numVerts=" + m.numvertices);
        }

        // --- Meshes ---
        System.out.println();
        System.out.println("--- Meshes (" + mdl.meshes.size() + ") ---");
        for (int i = 0; i < mdl.meshes.size(); i++) {
            MdlDataTypes.StudioMesh mesh = mdl.meshes.get(i);
            System.out.println("  [" + i + "] modelIdx=" + mesh.globalModelIndex
                + " material=" + mesh.material + " numVerts=" + mesh.numvertices
                + " vertOffset=" + mesh.vertexoffset + " materialType=" + mesh.materialtype
                + " meshId=" + mesh.meshid);
        }

        // --- Bones ---
        System.out.println();
        System.out.println("--- Bones (" + mdl.bones.size() + ") ---");
        for (int i = 0; i < mdl.bones.size(); i++) {
            MdlDataTypes.StudioBone b = mdl.bones.get(i);
            System.out.print("  [" + i + "] name='" + b.name + "' parent=" + b.parent);
            if (b.pos != null && b.pos.length >= 3) {
                System.out.print(" pos=(" + fmt(b.pos[0]) + ", " + fmt(b.pos[1]) + ", " + fmt(b.pos[2]) + ")");
            }
            if (b.quat != null && b.quat.length >= 4) {
                System.out.print(" quat=(" + fmt(b.quat[0]) + ", " + fmt(b.quat[1]) + ", " + fmt(b.quat[2]) + ", " + fmt(b.quat[3]) + ")");
            }
            System.out.println();
        }

        // --- Sequences ---
        System.out.println();
        System.out.println("--- Sequences (" + mdl.sequences.size() + ") ---");
        for (int i = 0; i < mdl.sequences.size(); i++) {
            MdlDataTypes.StudioSeqDesc seq = mdl.sequences.get(i);
            System.out.println("  [" + i + "] label='" + seq.label + "' numFrames=" + seq.numframes
                + " flags=0x" + Integer.toHexString(seq.flags)
                + " fps=(" + fmt(seq.fadeInTime[0]) + "/" + fmt(seq.fadeInTime[1]) + ")"
                + " activity=" + seq.activity + " actWeight=" + seq.actweight);
        }

        // --- Textures ---
        System.out.println();
        System.out.println("--- Textures (" + mdl.textures.size() + ") ---");
        for (int i = 0; i < mdl.textures.size(); i++) {
            MdlDataTypes.StudioTexture tex = mdl.textures.get(i);
            System.out.println("  [" + i + "] name='" + tex.name + "' flags=0x" + Integer.toHexString(tex.flags)
                + " dims=" + tex.width + "x" + tex.height);
        }

        // --- CdTextures ---
        if (!mdl.cdTextures.isEmpty()) {
            System.out.println();
            System.out.println("--- CdTextures (" + mdl.cdTextures.size() + ") ---");
            for (int i = 0; i < mdl.cdTextures.size(); i++) {
                System.out.println("  [" + i + "] '" + mdl.cdTextures.get(i) + "'");
            }
        }

        // --- Skin Table ---
        if (!mdl.skinTable.isEmpty()) {
            System.out.println();
            System.out.println("--- SkinTable (" + mdl.skinTable.size() + " entries) ---");
            StringBuilder sb = new StringBuilder("  ");
            for (int i = 0; i < mdl.skinTable.size(); i++) {
                if (i > 0 && i % 20 == 0) {
                    System.out.println(sb);
                    sb = new StringBuilder("  ");
                }
                sb.append(mdl.skinTable.get(i)).append(" ");
            }
            if (sb.length() > 2) System.out.println(sb);
        }

        // --- Attachments ---
        System.out.println();
        System.out.println("--- Attachments (" + mdl.attachments.size() + ") ---");
        for (int i = 0; i < mdl.attachments.size(); i++) {
            MdlDataTypes.StudioAttachment a = mdl.attachments.get(i);
            System.out.println("  [" + i + "] name='" + a.name + "' bone=" + a.attachmentbone
                + " flags=0x" + Integer.toHexString(a.flags)
                + " org=(" + fmt(a.org[0]) + ", " + fmt(a.org[1]) + ", " + fmt(a.org[2]) + ")");
        }

        // --- Hitbox Sets ---
        System.out.println();
        System.out.println("--- Hitbox Sets (" + mdl.hitboxSets.size() + ") ---");
        for (int i = 0; i < mdl.hitboxSets.size(); i++) {
            MdlDataTypes.StudioHitboxSet hs = mdl.hitboxSets.get(i);
            System.out.println("  [" + i + "] name='" + hs.name + "' numHitboxes=" + hs.numhitboxes);
            for (int h = 0; h < hs.hitboxes.size(); h++) {
                MdlDataTypes.Bbox bbox = hs.hitboxes.get(h);
                System.out.println("    [" + h + "] bone=" + bbox.bone + " group=" + bbox.group
                    + " bbmin=(" + fmt(bbox.bbmin[0]) + ", " + fmt(bbox.bbmin[1]) + ", " + fmt(bbox.bbmin[2]) + ")"
                    + " bbmax=(" + fmt(bbox.bbmax[0]) + ", " + fmt(bbox.bbmax[1]) + ", " + fmt(bbox.bbmax[2]) + ")");
            }
        }

        // --- Flex Descriptors ---
        System.out.println();
        System.out.println("--- Flex Descriptors (" + mdl.flexDescs.size() + ") ---");
        for (int i = 0; i < mdl.flexDescs.size(); i++) {
            MdlDataTypes.StudioFlexDesc fd = mdl.flexDescs.get(i);
            System.out.println("  [" + i + "] name='" + fd.name + "'");
        }

        // --- Flex Controllers ---
        if (!mdl.flexControllers.isEmpty()) {
            System.out.println();
            System.out.println("--- Flex Controllers (" + mdl.flexControllers.size() + ") ---");
            for (int i = 0; i < mdl.flexControllers.size(); i++) {
                MdlDataTypes.StudioFlexController fc = mdl.flexControllers.get(i);
                System.out.println("  [" + i + "] name='" + fc.name + "' range=[" + fmt(fc.min[0]) + "," + fmt(fc.max[0]) + "]");
            }
        }

        // --- Flex Rules ---
        if (!mdl.flexRules.isEmpty()) {
            System.out.println();
            System.out.println("--- Flex Rules (" + mdl.flexRules.size() + ") ---");
            for (int i = 0; i < mdl.flexRules.size(); i++) {
                MdlDataTypes.StudioFlexRule fr = mdl.flexRules.get(i);
                System.out.println("  [" + i + "] flex=" + fr.flex + " numOps=" + fr.numops);
            }
        }

        // --- Local Animations ---
        System.out.println();
        System.out.println("--- Local Animations (" + mdl.localAnims.size() + ") ---");
        for (int i = 0; i < mdl.localAnims.size(); i++) {
            MdlDataTypes.StudioLocalAnim anim = mdl.localAnims.get(i);
            System.out.println("  [" + i + "] name='" + anim.name + "' numFrames=" + anim.numFrames
                + " fps=" + fmt(anim.fps) + " flags=0x" + Integer.toHexString(anim.flags)
                + " numSegments=" + anim.numSegments + " animBlock=" + anim.animBlock
                + " animOffset=0x" + Integer.toHexString(anim.animOffset));
        }

        // --- Bone Controllers ---
        if (!mdl.boneControllers.isEmpty()) {
            System.out.println();
            System.out.println("--- Bone Controllers (" + mdl.boneControllers.size() + ") ---");
            for (int i = 0; i < mdl.boneControllers.size(); i++) {
                MdlDataTypes.StudioBoneController bc = mdl.boneControllers.get(i);
                System.out.println("  [" + i + "] bone=" + bc.bone + " channel=" + bc.channel
                    + " flags=0x" + Integer.toHexString(bc.flags));
            }
        }

        // --- IK Chains ---
        if (!mdl.ikChains.isEmpty()) {
            System.out.println();
            System.out.println("--- IK Chains (" + mdl.ikChains.size() + ") ---");
            for (int i = 0; i < mdl.ikChains.size(); i++) {
                MdlDataTypes.StudioIKChain ik = mdl.ikChains.get(i);
                System.out.println("  [" + i + "] name='" + ik.name + "' chain=" + ik.chain
                    + " numLinks=" + ik.numlinks);
            }
        }

        // --- Pose Parameters ---
        if (!mdl.poseParams.isEmpty()) {
            System.out.println();
            System.out.println("--- Pose Parameters (" + mdl.poseParams.size() + ") ---");
            for (int i = 0; i < mdl.poseParams.size(); i++) {
                MdlDataTypes.StudioPoseParam pp = mdl.poseParams.get(i);
                System.out.println("  [" + i + "] name='" + pp.name + "' type=" + pp.type
                    + " range=[" + fmt(pp.start) + ", " + fmt(pp.end) + "] loop=" + pp.loop);
            }
        }

        // --- Local Nodes ---
        if (!mdl.localNodes.isEmpty()) {
            System.out.println();
            System.out.println("--- Local Nodes (" + mdl.localNodes.size() + ") ---");
            for (int i = 0; i < mdl.localNodes.size(); i++) {
                MdlDataTypes.StudioLocalNode n = mdl.localNodes.get(i);
                System.out.println("  [" + i + "] name='" + n.name + "' parent=" + n.parent);
            }
        }

        // --- IK Autoplay Locks ---
        if (!mdl.ikAutoplayLocks.isEmpty()) {
            System.out.println();
            System.out.println("--- IK Autoplay Locks (" + mdl.ikAutoplayLocks.size() + ") ---");
            for (int i = 0; i < mdl.ikAutoplayLocks.size(); i++) {
                MdlDataTypes.StudioIKAutoplayLock lock = mdl.ikAutoplayLocks.get(i);
                System.out.println("  [" + i + "] ikChain=" + lock.ikChainIndex
                    + " lockCount=" + lock.lockCount + " threshold=" + fmt(lock.threshold));
            }
        }

        // --- Mouths ---
        if (!mdl.mouths.isEmpty()) {
            System.out.println();
            System.out.println("--- Mouths (" + mdl.mouths.size() + ") ---");
            for (int i = 0; i < mdl.mouths.size(); i++) {
                MdlDataTypes.StudioMouth m = mdl.mouths.get(i);
                System.out.println("  [" + i + "] bone=" + m.bone);
            }
        }

        // --- Key Values ---
        if (mdl.keyValues != null && !mdl.keyValues.isEmpty()) {
            System.out.println();
            System.out.println("--- Key Values (" + mdl.keyValues.length() + " chars) ---");
            System.out.println("  " + mdl.keyValues);
        }

        // --- Surface Prop ---
        if (mdl.surfaceProp != null && !mdl.surfaceProp.isEmpty()) {
            System.out.println();
            System.out.println("--- Surface Prop ---");
            System.out.println("  " + mdl.surfaceProp);
        }

        // --- Include Models ---
        if (!mdl.includeModels.isEmpty()) {
            System.out.println();
            System.out.println("--- Include Models (" + mdl.includeModels.size() + ") ---");
            for (int i = 0; i < mdl.includeModels.size(); i++) {
                System.out.println("  [" + i + "] '" + mdl.includeModels.get(i) + "'");
            }
        }

        // --- Eyeballs ---
        if (!mdl.eyeballs.isEmpty()) {
            System.out.println();
            System.out.println("--- Eyeballs (" + mdl.eyeballs.size() + ") ---");
            for (int i = 0; i < mdl.eyeballs.size(); i++) {
                MdlDataTypes.StudioEyeball e = mdl.eyeballs.get(i);
                System.out.println("  [" + i + "] bone=" + e.bone + " radius=" + fmt(e.radius)
                    + " irisMaterial=" + e.irisMaterial);
            }
        }

        System.out.println();
        System.out.println("[OK] MDL parsed successfully.");
    }

    private static void dumpHeader(MdlDataTypes.StudioHeader h) {
        System.out.println();
        System.out.println("--- Header ---");
        System.out.println("  id=0x" + Integer.toHexString(h.id) + " (" + intToMagic(h.id) + ")");
        System.out.println("  version=" + h.version);
        System.out.println("  checksum=" + h.checksum);
        System.out.println("  name='" + h.name + "'");
        System.out.println("  dataLength=" + h.dataLength);
        if (h.eyeposition != null && h.eyeposition.length >= 3)
            System.out.println("  eyePosition=(" + fmt(h.eyeposition[0]) + ", " + fmt(h.eyeposition[1]) + ", " + fmt(h.eyeposition[2]) + ")");
        if (h.illumposition != null && h.illumposition.length >= 3)
            System.out.println("  illumPosition=(" + fmt(h.illumposition[0]) + ", " + fmt(h.illumposition[1]) + ", " + fmt(h.illumposition[2]) + ")");
        if (h.hull_min != null && h.hull_min.length >= 3)
            System.out.println("  hullMin=(" + fmt(h.hull_min[0]) + ", " + fmt(h.hull_min[1]) + ", " + fmt(h.hull_min[2]) + ")");
        if (h.hull_max != null && h.hull_max.length >= 3)
            System.out.println("  hullMax=(" + fmt(h.hull_max[0]) + ", " + fmt(h.hull_max[1]) + ", " + fmt(h.hull_max[2]) + ")");
        if (h.view_bbmin != null && h.view_bbmin.length >= 3)
            System.out.println("  viewBBMin=(" + fmt(h.view_bbmin[0]) + ", " + fmt(h.view_bbmin[1]) + ", " + fmt(h.view_bbmin[2]) + ")");
        if (h.view_bbmax != null && h.view_bbmax.length >= 3)
            System.out.println("  viewBBMax=(" + fmt(h.view_bbmax[0]) + ", " + fmt(h.view_bbmax[1]) + ", " + fmt(h.view_bbmax[2]) + ")");
        System.out.println("  flags=0x" + Integer.toHexString(h.flags));
        System.out.println("  numBones=" + h.numbones + " boneIndex=0x" + Integer.toHexString(h.boneindex));
        System.out.println("  numBoneControllers=" + h.numbonecontrollers + " boneControllerIndex=0x" + Integer.toHexString(h.bonecontrollerindex));
        System.out.println("  numHitboxSets=" + h.numhitboxsets + " hitboxSetIndex=0x" + Integer.toHexString(h.hitboxsetindex));
        System.out.println("  numLocalAnim=" + h.numlocalanim + " localAnimIndex=0x" + Integer.toHexString(h.localanimindex));
        System.out.println("  numLocalSeq=" + h.numlocalseq + " localSeqIndex=0x" + Integer.toHexString(h.localseqindex));
        System.out.println("  activityListVersion=" + h.activitylistversion);
        System.out.println("  eventsIndexed=" + h.eventsindexed);
        System.out.println("  numTextures=" + h.numtextures + " textureIndex=0x" + Integer.toHexString(h.textureindex));
        System.out.println("  numCdTextures=" + h.numcdtextures + " cdTextureIndex=0x" + Integer.toHexString(h.cdtextureindex));
        System.out.println("  numSkinRef=" + h.numskinref + " numSkinFamilies=" + h.numskinfamilies + " skinIndex=0x" + Integer.toHexString(h.skinindex));
        System.out.println("  numBodyParts=" + h.numbodyparts + " bodyPartIndex=0x" + Integer.toHexString(h.bodypartindex));
        System.out.println("  numLocalAttachments=" + h.numlocalattachments + " localAttachmentIndex=0x" + Integer.toHexString(h.localattachmentindex));
        System.out.println("  numLocalNodes=" + h.numlocalnodes + " localNodeIndex=0x" + Integer.toHexString(h.localnodeindex));
        System.out.println("  localNodeNameIndex=0x" + Integer.toHexString(h.localnodenameindex));
        System.out.println("  numFlexDesc=" + h.numflexdesc + " flexDescIndex=0x" + Integer.toHexString(h.flexdescindex));
        System.out.println("  numFlexControllers=" + h.numflexcontrollers + " flexControllerIndex=0x" + Integer.toHexString(h.flexcontrollerindex));
        System.out.println("  numFlexRules=" + h.numflexrules + " flexRuleIndex=0x" + Integer.toHexString(h.flexruleindex));
        System.out.println("  numIKChains=" + h.numikchains + " ikChainIndex=0x" + Integer.toHexString(h.ikchainindex));
        System.out.println("  numMouths=" + h.nummouths + " mouthIndex=0x" + Integer.toHexString(h.mouthindex));
        System.out.println("  numLocalPoseParams=" + h.numlocalposeparameters + " localPoseParamIndex=0x" + Integer.toHexString(h.localposeparamindex));
        System.out.println("  surfacePropIndex=0x" + Integer.toHexString(h.surfacepropindex));
        System.out.println("  keyValueIndex=0x" + Integer.toHexString(h.keyvalueindex) + " keyValueSize=" + h.keyvaluesize);
        System.out.println("  numLocalIKAutoplayLocks=" + h.numlocalikautoplaylocks + " localIKAutoplayLockIndex=0x" + Integer.toHexString(h.localikautoplaylockindex));
        System.out.println("  mass=" + h.mass + " contents=0x" + Integer.toHexString(h.contents));
        System.out.println("  numIncludeModels=" + h.numincludemodels + " includeModelIndex=0x" + Integer.toHexString(h.includemodelindex));
        System.out.println("  virtualModel=" + h.virtualModel);
        System.out.println("  szAnimBlockNameIndex=" + h.szanimblocknameindex);
        System.out.println("  numAnimBlocks=" + h.numanimblocks + " animBlockIndex=0x" + Integer.toHexString(h.animblockindex));
        System.out.println("  animBlockModel=" + h.animblockModel);
        System.out.println("  boneTableNameIndex=" + h.bonetablenameindex);
        System.out.println("  vertexBase=" + h.vertexbase + " offsetBase=" + h.offsetbase);
        System.out.println("  directionalDotProduct=" + (h.directionaldotproduct & 0xFF));
        System.out.println("  rootLod=" + (h.rootLod & 0xFF));
        System.out.println("  numAllowedRootLods=" + (h.numAllowedRootLods & 0xFF));
        System.out.println("  flexControllerUIIndex=0x" + Integer.toHexString(h.flexcontrolleruiindex));
        System.out.println("  vertAnimFixedPointScale=" + h.vertAnimFixedPointScale);
        System.out.println("  unused3=" + h.unused3);
        System.out.println("  studioHdr2Index=0x" + Integer.toHexString(h.studiohdr2index));
    }

    private static void dumpHdr2(MdlDataTypes.StudioHdr2 hdr2) {
        if (hdr2 == null || !hdr2.hasData) {
            System.out.println();
            System.out.println("--- StudioHdr2: not present ---");
            return;
        }
        System.out.println();
        System.out.println("--- StudioHdr2 ---");
        System.out.println("  numSkins=" + hdr2.numSkins);
        System.out.println("  skinReplacementIndex=0x" + Integer.toHexString(hdr2.skinReplacementIndex));
        System.out.println("  numSrcBoneTransforms=" + hdr2.numSrcBoneTransforms);
        System.out.println("  srcBoneTransformIndex=" + hdr2.srcBoneTransformIndex);
        System.out.println("  numFlexControllerUI=" + hdr2.numFlexControllerUI);
        System.out.println("  flexControllerUIOffset=0x" + Integer.toHexString(hdr2.flexControllerUIOffset));
        System.out.println("  eyeControllerNumHistories=" + hdr2.eyeControllerNumHistories);
        System.out.println("  eyeControllerHistoryOffset=0x" + Integer.toHexString(hdr2.eyeControllerHistoryOffset));
        if (hdr2.skinReplacementCounts != null) {
            System.out.print("  skinReplacementCounts=[");
            for (int i = 0; i < hdr2.skinReplacementCounts.length; i++) {
                if (i > 0) System.out.print(", ");
                System.out.print(hdr2.skinReplacementCounts[i]);
            }
            System.out.println("]");
        }
    }

    // ---------------------------------------------------------------
    //  VVD
    // ---------------------------------------------------------------

    private static void dumpVvd(String path) {
        byte[] data = readFile(path);
        if (data == null) return;

        VvdParser.ParsedVvd vvd;
        try {
            vvd = VvdParser.parse(data);
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to parse VVD: " + e.getMessage());
            e.printStackTrace(System.out);
            return;
        }

        VvdParser.VvdHeader h = vvd.header;
        System.out.println();
        System.out.println("--- VVD Header ---");
        System.out.println("  id=0x" + Integer.toHexString(h.id) + " (" + intToMagic(h.id) + ")");
        System.out.println("  version=" + h.version);
        System.out.println("  checksum=" + h.checksum);
        System.out.println("  numLODs=" + h.numLODs);
        System.out.print("  numLODVertices=[");
        if (h.numLODVertices != null) {
            for (int i = 0; i < Math.min(h.numLODVertices.length, 8); i++) {
                if (i > 0) System.out.print(", ");
                System.out.print("LOD" + i + "=" + h.numLODVertices[i]);
            }
        }
        System.out.println("]");
        System.out.println("  numFixups=" + h.numFixups);
        System.out.println("  fixupTableStart=0x" + Integer.toHexString(h.fixupTableStart));
        System.out.println("  vertexDataStart=0x" + Integer.toHexString(h.vertexDataStart));
        System.out.println("  tangentDataStart=0x" + Integer.toHexString(h.tangentDataStart));

        System.out.println();
        System.out.println("--- VVD Fixups (" + vvd.fixups.size() + ") ---");
        for (int i = 0; i < vvd.fixups.size(); i++) {
            VvdParser.VvdFixup f = vvd.fixups.get(i);
            System.out.println("  [" + i + "] lod=" + f.lodIndex
                + " srcVertex=" + f.sourceVertexID + " count=" + f.numVertexes);
        }

        System.out.println();
        System.out.println("--- VVD Vertices ---");
        System.out.println("  totalVertices=" + vvd.vertices.size());
        for (int lod = 0; lod < vvd.lodVertices.size(); lod++) {
            System.out.println("  LOD" + (lod + 1) + " vertices=" + vvd.lodVertices.get(lod).size());
        }
        // Print first few vertices as sample
        int sampleCount = Math.min(5, vvd.vertices.size());
        if (sampleCount > 0) {
            System.out.println("  First " + sampleCount + " vertices:");
            for (int i = 0; i < sampleCount; i++) {
                VvdParser.StudioVertexExt v = vvd.vertices.get(i);
                System.out.println("    [" + i + "] pos=(" + fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z) + ")"
                    + " normal=(" + fmt(v.nx) + ", " + fmt(v.ny) + ", " + fmt(v.nz) + ")"
                    + " uv=(" + fmt(v.u) + ", " + fmt(v.v) + ")");
            }
        }

        System.out.println();
        System.out.println("[OK] VVD parsed successfully.");
    }

    // ---------------------------------------------------------------
    //  VTX
    // ---------------------------------------------------------------

    private static void dumpVtx(String path) {
        byte[] data = readFile(path);
        if (data == null) return;

        VtxParser.ParsedVtx vtx;
        try {
            vtx = VtxParser.parse(data);
        } catch (Exception e) {
            System.out.println("[ERROR] Failed to parse VTX: " + e.getMessage());
            e.printStackTrace(System.out);
            return;
        }

        System.out.println();
        System.out.println("--- VTX Header Info ---");
        System.out.println("  version=" + vtx.version);
        System.out.println("  checksum=" + vtx.checksum);
        System.out.println("  numLODs=" + vtx.numLODs);
        System.out.println("  numBodyParts=" + vtx.numBodyParts);

        System.out.println();
        System.out.println("--- VTX BodyPart / Mesh / Triangle Summary ---");
        System.out.println("  BodyPart count=" + vtx.numBodyParts);
        System.out.println("  Mesh count (LOD0)=" + vtx.meshTriangles.size());

        int totalTriangles = 0;
        for (int i = 0; i < vtx.meshTriangles.size(); i++) {
            int triCount = vtx.meshTriangles.get(i).size();
            totalTriangles += triCount;
            System.out.println("  Mesh [" + i + "]: " + triCount + " triangles");
        }
        System.out.println("  Total triangles (LOD0): " + totalTriangles);

        // LOD triangle summaries
        if (!vtx.lodMeshTriangles.isEmpty()) {
            System.out.println();
            System.out.println("--- VTX LOD Triangle Summaries ---");
            for (int lod = 0; lod < vtx.lodMeshTriangles.size(); lod++) {
                List<List<VtxParser.VtxTriangle>> lodMeshes = vtx.lodMeshTriangles.get(lod);
                int lodTriCount = 0;
                for (int m = 0; m < lodMeshes.size(); m++) {
                    lodTriCount += lodMeshes.get(m).size();
                }
                System.out.println("  LOD" + (lod + 1) + ": " + lodMeshes.size() + " meshes, "
                    + lodTriCount + " triangles");
            }
        }

        System.out.println();
        System.out.println("[OK] VTX parsed successfully.");
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static byte[] readFile(String path) {
        if (path == null || path.isEmpty()) {
            System.out.println("[ERROR] No path specified.");
            return null;
        }
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            System.out.println("[ERROR] File not found: " + p.toAbsolutePath());
            return null;
        }
        if (!Files.isReadable(p)) {
            System.out.println("[ERROR] File not readable: " + p.toAbsolutePath());
            return null;
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            System.out.println("[ERROR] Failed to read file: " + p.toAbsolutePath() + " - " + e.getMessage());
            return null;
        }
    }

    private static String fmt(float f) {
        if (Float.isNaN(f)) return "NaN";
        if (Float.isInfinite(f)) return f > 0 ? "Inf" : "-Inf";
        // Show 6 decimal places, strip trailing zeros
        String s = String.format("%.6f", f);
        int dot = s.indexOf('.');
        if (dot >= 0) {
            int end = s.length() - 1;
            while (end > dot && s.charAt(end) == '0') end--;
            if (end == dot) end--;
            s = s.substring(0, end + 1);
        }
        return s;
    }

    private static String intToMagic(int id) {
        byte[] b = new byte[4];
        b[0] = (byte) (id & 0xFF);
        b[1] = (byte) ((id >> 8) & 0xFF);
        b[2] = (byte) ((id >> 16) & 0xFF);
        b[3] = (byte) ((id >> 24) & 0xFF);
        return "'" + new String(b, java.nio.charset.StandardCharsets.ISO_8859_1) + "'";
    }
}
