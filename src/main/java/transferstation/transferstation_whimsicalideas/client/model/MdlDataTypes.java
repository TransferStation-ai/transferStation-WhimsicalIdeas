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

    public static class StudioHeader extends Header {}

    public static class BodyPart {
        public int sznameindex;
        public int nummodels;
        public int baseIndex;
        public int modelindex;
        public int fileOffset;
        public String name;
    }

    public static class StudioBodyPart extends BodyPart {}

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

    public static class StudioModel extends Model {}

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

    public static class StudioMesh extends Mesh {}

    public static class Vertex {
        public float x, y, z;
        public float nx, ny, nz;
        public float u, v;
    }

    public static class StudioVertex extends Vertex {}

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

    public static class StudioBone extends Bone {}

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

    public static class StudioEyeball extends Eyeball {}

    public static class Texture {
        public String name;
        public int flags;
        public int width;
        public int height;
    }

    public static class StudioTexture extends Texture {}

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

    public static class StudioHdr2 extends Hdr2 {}

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

    public static class StudioAttachment extends Attachment {}

    public static class BoneController {
        public int bone;
        public int channel;
        public int flags;
        public float[] start;
        public float[] end;
        public int rest;
        public int inputField;
    }

    public static class StudioBoneController extends BoneController {}

    public static class HitboxSet {
        public int sznameindex;
        public String name;
        public int numhitboxes;
        public int hitboxindex;
        public List<Bbox> hitboxes = new ArrayList<>();
    }

    public static class StudioHitboxSet extends HitboxSet {}

    public static class Bbox {
        public int bone;
        public int group;
        public float[] bbmin;
        public float[] bbmax;
        public int sznameindex;
        public String name;
    }

    public static class StudioBbox extends Bbox {}

    public static class SeqDesc {
        public int baseptr;
        public int sznameindex;
        public String label;
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

    public static class StudioSeqDesc extends SeqDesc {}

    public static class AnimEvent {
        public int cycle;
        public int eventIndex;
        public int eventType;
        public byte[] options;
        public int sznameindex;
        public String name;
    }

    public static class StudioAnimEvent extends AnimEvent {}

    public static class IKChain {
        public int sznameindex;
        public String name;
        public int chain;
        public int numlinks;
        public int linkindex;
        public int fileOffset;
        public List<IKLink> links = new ArrayList<>();
    }

    public static class StudioIKChain extends IKChain {}

    public static class IKLink {
        public int bone;
        public float[] kneeDir;
        public float[] limits;
        public int unused;
    }

    public static class StudioIKLink extends IKLink {}

    public static class FlexDesc {
        public int sznameindex;
        public String name;
    }

    public static class StudioFlexDesc extends FlexDesc {}

    public static class FlexController {
        public int sznameindex;
        public String name;
        public int[] localToGlobal;
        public float[] min;
        public float[] max;
    }

    public static class StudioFlexController extends FlexController {}

    public static class FlexRule {
        public int flex;
        public int numops;
        public int opindex;
        public int[] ops;
    }

    public static class StudioFlexRule extends FlexRule {}

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

    public static class StudioLocalAnim extends LocalAnim {}

    public static class PoseParam {
        public String name;
        public int type;
        public float start;
        public float end;
        public int loop;
    }

    public static class StudioPoseParam extends PoseParam {}

    public static class LocalNode {
        public String name;
        public int parent;
    }

    public static class StudioLocalNode extends LocalNode {}

    public static class IKAutoplayLock {
        public int ikChainIndex;
        public int lockCount;
        public float threshold;
    }

    public static class StudioIKAutoplayLock extends IKAutoplayLock {}

    public static class Mouth {
        public int bone;
        public float[] flexibleOffsets;
    }

    public static class StudioMouth extends Mouth {}

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

    public static class StudioSrcBoneTransform extends SrcBoneTransform {}

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

    public static class StudioAnimFrameBone extends AnimFrameBone {}

    public static class AnimFrameData {
        public int frame;
        public List<AnimFrameBone> boneTransforms = new ArrayList<>();
    }

    public static class StudioAnimFrameData extends AnimFrameData {}

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

    public static class StudioSequenceAnimData extends SequenceAnimData {}

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
        public List<StudioAttachment> attachments = new ArrayList<>();
        public List<StudioBoneController> boneControllers = new ArrayList<>();
        public List<StudioHitboxSet> hitboxSets = new ArrayList<>();
        public List<StudioSeqDesc> sequences = new ArrayList<>();
        public List<StudioIKChain> ikChains = new ArrayList<>();
        public List<StudioFlexDesc> flexDescs = new ArrayList<>();
        public List<StudioFlexController> flexControllers = new ArrayList<>();
        public List<StudioFlexRule> flexRules = new ArrayList<>();

        public int modelSize = 148;
        public int meshSize = 116;
        public int boneSize = 216;
        public int seqdescSize = 220;

        public List<StudioLocalAnim> localAnims = new ArrayList<>();
        public List<StudioPoseParam> poseParams = new ArrayList<>();
        public List<StudioLocalNode> localNodes = new ArrayList<>();
        public List<StudioIKAutoplayLock> ikAutoplayLocks = new ArrayList<>();
        public List<StudioMouth> mouths = new ArrayList<>();
        public String keyValues;
        public String surfaceProp;

        public List<StudioSrcBoneTransform> srcBoneTransforms = new ArrayList<>();
        public List<StudioSequenceAnimData> sequenceAnimData = new ArrayList<>();
        public List<Integer> referenceSequenceIndices = new ArrayList<>();
        public List<Integer> aPoseSequenceIndices = new ArrayList<>();
    }
}
