package transferstation.transferstation_whimsicalideas.client.model;

import java.util.ArrayList;
import java.util.List;

public class MdlDataTypes {

    public static class Header {
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

    public static class BodyPart {
        public int sznameindex;
        public int nummodels;
        public int baseIndex;
        public int modelindex;
        public int fileOffset;
        public String name;
    }

    public static class Model {
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

    public static class Mesh {
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

    public static class Vertex {
        public float x, y, z;
        public float nx, ny, nz;
        public float u, v;
    }

    public static class Bone {
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

    public static class Eyeball {
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

    public static class Texture {
        public String name;
        public int flags;
        public int width;
        public int height;
    }

    public static class Hdr2 {
        public int numSkins;
        public int skinReplacementIndex;
        public int numSrcBoneTransforms;
        public int srcBoneTransformIndex;
        public int numFlexControllerUI;
        public int flexControllerUIOffset;
        public int eyeControllerNumHistories;
        public int eyeControllerHistoryOffset;
        public boolean hasData;
        public int[] skinReplacementCounts;
        public int[] skinReplacementTables;
    }

    public static class Attachment {
        public int sznameindex;
        public String name;
        public int flags;
        public int attachmentbone;
        public float[] org;
        public float[] vectors;
        public float[] quat;
        public float[] rot;
        public int fileOffset;
    }

    public static class BoneController {
        public int bone;
        public int channel;
        public int flags;
        public float[] start;
        public float[] end;
        public int rest;
        public int inputField;
    }

    public static class HitboxSet {
        public int sznameindex;
        public String name;
        public int numhitboxes;
        public int hitboxindex;
        public List<Bbox> hitboxes = new ArrayList<>();
    }

    public static class Bbox {
        public int bone;
        public int group;
        public float[] bbmin;
        public float[] bbmax;
        public int sznameindex;
        public String name;
    }

    public static class SeqDesc {
        public int baseptr;
        public int sznameindex;
        public String label;
        public int szactivitynameindex;
        public int activity;
        public int actweight;
        public int[] events;
        public int numevents;
        public int eventindex;
        public int numframes;
        public int numpivots;
        public int pivotindex;
        public int motiontype;
        public int motionbone;
        public float[] linearmovement;
        public int automoveposindex;
        public float[] bbmin;
        public float[] bbmax;
        public int numblends;
        public int animindex;
        public int[] blend;
        public float[] blendpos;
        public int numlocalhints;
        public int localhintindex;
        public int groupSize;
        public int numIK;
        public int IKIndex;
        public int flags;
        public float[] fadeInTime;
        public float[] fadeOutTime;
        public int localEntryNode;
        public int localExitNode;
        public int nodeFlags;
        public float entryPhase;
        public float exitPhase;
        public float lastFrame;
        public int nextSeq;
        public int pose;
        public float[] poseKey;
        public int numIKLocks;
        public int IKLockIndex;
        public float[] keyValueIndex;
        public int keyValueSize;
        public int paramValue;
    }

    public static class AnimEvent {
        public int cycle;
        public int eventIndex;
        public int eventType;
        public byte[] options;
        public int sznameindex;
        public String name;
    }

    public static class IKChain {
        public int sznameindex;
        public String name;
        public int chain;
        public int numlinks;
        public int linkindex;
        public int fileOffset;
        public List<IKLink> links = new ArrayList<>();
    }

    public static class IKLink {
        public int bone;
        public float[] kneeDir;
        public float[] limits;
        public int unused;
    }

    public static class FlexDesc {
        public int sznameindex;
        public String name;
    }

    public static class FlexController {
        public int sznameindex;
        public String name;
        public int[] localToGlobal;
        public float[] min;
        public float[] max;
    }

    public static class FlexRule {
        public int flex;
        public int numops;
        public int opindex;
        public int[] ops;
    }

    public static class LocalAnim {
        public String name;
        public int animBlock;
        public int animOffset;
        public int numFrames;
        public int numSegments;
        public int segmentIndex;
        public int flags;
        public float fps;
        public int[] animBlocks;
    }

    public static class PoseParam {
        public String name;
        public int type;
        public float start;
        public float end;
        public int loop;
    }

    public static class LocalNode {
        public String name;
        public int parent;
    }

    public static class IKAutoplayLock {
        public int ikChainIndex;
        public int lockCount;
        public float threshold;
    }

    public static class Mouth {
        public int bone;
        public float[] flexibleOffsets;
    }

    public static class SrcBoneTransform {
        public float[] pos;
        public float[] quat;
        public float[] scale;

        public SrcBoneTransform() {
            this.pos = new float[3];
            this.quat = new float[4];
            this.scale = new float[3];
        }
    }

    public static class AnimFrameBone {
        public int boneIndex;
        public String boneName;
        public float[] pos;
        public float[] quat;
        public float[] scale;

        public AnimFrameBone() {
            this.pos = new float[3];
            this.quat = new float[4];
            this.scale = new float[]{1, 1, 1};
        }
    }

    public static class AnimFrameData {
        public int frame;
        public List<AnimFrameBone> boneTransforms = new ArrayList<>();
    }

    public static class SequenceAnimData {
        public boolean isReference;
        public boolean isAPose;
        public List<AnimFrameData> frames = new ArrayList<>();

        public AnimFrameData getFrame(int frameIndex) {
            if (frames.isEmpty()) return null;
            if (frameIndex < 0) frameIndex = 0;
            if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
            return frames.get(frameIndex);
        }
    }

    public enum SequenceType {
        NORMAL,
        REFERENCE,
        A_POSE
    }

    public static SequenceType classifySequence(SeqDesc seq) {
        if (seq.label == null || seq.label.isEmpty()) return SequenceType.NORMAL;
        String name = seq.label.toLowerCase().trim();
        if (name.equals("ref") || name.equals("reference") || name.equals("bindpose")
            || name.equals("bind_pose") || name.startsWith("ref_") || name.contains("_ref")
            || name.contains("reference") || name.equals("default")) {
            return SequenceType.REFERENCE;
        }
        if (name.equals("a_pose") || name.equals("apose") || name.equals("a-pose")
            || name.startsWith("a_pose") || name.contains("_apose")) {
            return SequenceType.A_POSE;
        }
        return SequenceType.NORMAL;
    }

    public static boolean nameMatches(String name, String... keywords) {
        if (name == null) return false;
        String lower = name.toLowerCase().trim();
        for (String kw : keywords) {
            if (lower.equals(kw) || lower.startsWith(kw + "_") || lower.contains("_" + kw)
                || lower.startsWith(kw + "-") || lower.endsWith("_" + kw) || lower.endsWith("-" + kw)) {
                return true;
            }
        }
        return false;
    }

    public static class ParsedModel {
        public Header header;
        public Hdr2 hdr2;
        public List<BodyPart> bodyParts = new ArrayList<>();
        public List<Model> models = new ArrayList<>();
        public List<Mesh> meshes = new ArrayList<>();
        public List<Vertex> vertices = new ArrayList<>();
        public List<Integer> indices = new ArrayList<>();
        public List<Bone> bones = new ArrayList<>();
        public List<Eyeball> eyeballs = new ArrayList<>();
        public List<Integer> meshTrianglesOffset = new ArrayList<>();
        public List<Texture> textures = new ArrayList<>();
        public List<String> cdTextures = new ArrayList<>();
        public List<Integer> skinTable = new ArrayList<>();
        public int vvdVertexCount;
        public List<List<VtxParser.VtxTriangle>> vtxTriangles = new ArrayList<>();
        public List<String> includeModels = new ArrayList<>();
        public List<Attachment> attachments = new ArrayList<>();
        public List<BoneController> boneControllers = new ArrayList<>();
        public List<HitboxSet> hitboxSets = new ArrayList<>();
        public List<SeqDesc> sequences = new ArrayList<>();
        public List<IKChain> ikChains = new ArrayList<>();
        public List<FlexDesc> flexDescs = new ArrayList<>();
        public List<FlexController> flexControllers = new ArrayList<>();
        public List<FlexRule> flexRules = new ArrayList<>();

        public int modelSize = 148;
        public int meshSize = 116;
        public int boneSize = 216;
        public int seqdescSize = 220;

        public List<LocalAnim> localAnims = new ArrayList<>();
        public List<PoseParam> poseParams = new ArrayList<>();
        public List<LocalNode> localNodes = new ArrayList<>();
        public List<IKAutoplayLock> ikAutoplayLocks = new ArrayList<>();
        public List<Mouth> mouths = new ArrayList<>();
        public String keyValues;
        public String surfaceProp;

        public List<SrcBoneTransform> srcBoneTransforms = new ArrayList<>();
        public List<SequenceAnimData> sequenceAnimData = new ArrayList<>();
        public List<Integer> referenceSequenceIndices = new ArrayList<>();
        public List<Integer> aPoseSequenceIndices = new ArrayList<>();

        public List<MdlProceduralBones.AxisInterpBone> axisInterpBones = new ArrayList<>();
        public List<MdlProceduralBones.QuatInterpBone> quatInterpBones = new ArrayList<>();
        public List<MdlProceduralBones.JiggleBone> jiggleBones = new ArrayList<>();
        public List<MdlProceduralBones.AimAtBone> aimAtBones = new ArrayList<>();

        public List<java.util.List<MdlSequenceData.IKRule>> sequenceIKRules = new ArrayList<>();
        public List<java.util.List<MdlSequenceData.Autolayer>> sequenceAutolayers = new ArrayList<>();
        public List<java.util.List<MdlSequenceData.ActivityModifier>> sequenceActivityModifiers = new ArrayList<>();
        public List<java.util.List<MdlSequenceData.Movement>> sequenceMovements = new ArrayList<>();
        public List<MdlSequenceData.LocalHierarchy> localHierarchies = new ArrayList<>();

        public List<java.util.List<MdlFlexAnimation.FlexAnimation>> meshFlexAnimations = new ArrayList<>();
    }
}
