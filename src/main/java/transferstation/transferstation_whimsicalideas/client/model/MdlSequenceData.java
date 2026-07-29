package transferstation.transferstation_whimsicalideas.client.model;

public class MdlSequenceData {

    public static class IKRule {
        public int chain;
        public int bone;
        public int slot;
        public int type;
        public float height;
        public float radius;
        public float floor;
        public float[] pos = new float[3];
        public float[] quat = new float[4];
        public float start, peak, tail, end;
        public float contact, drop, top;
        public String attachment;
        public float[] errorScale = new float[6];
        public short[] errorOffset = new short[6];
    }

    public static class Autolayer {
        public short sequence;
        public short pose;
        public int flags;
        public float start, peak, tail, end;
    }

    public static class ActivityModifier {
        public String name;
    }

    public static class Movement {
        public int endframe;
        public int motionflags;
        public float v0, v1;
        public float angle;
        public float[] vector = new float[3];
        public float[] position = new float[3];
    }

    public static class LocalHierarchy {
        public int bone;
        public int newParent;
        public float start, peak, tail, end;
        public int startFrame;
    }

    static class SequenceDataParser {
        private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

        private static final int IKRULE_SIZE = 96;

        public static void parseIKRules(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.sequences.isEmpty()) return;
            int seqdescSize = result.seqdescSize;
            int localSeqIndex = result.header.localseqindex;
            if (localSeqIndex <= 0) return;

            for (int si = 0; si < result.sequences.size(); si++) {
                java.util.List<IKRule> rules = new java.util.ArrayList<>();
                MdlDataTypes.SeqDesc seq = result.sequences.get(si);
                if (seq.numIK <= 0 || seq.IKIndex <= 0) {
                    result.sequenceIKRules.add(rules);
                    continue;
                }

                int seqEntryOff = localSeqIndex + si * seqdescSize;
                int ikRuleDataAddr = seqEntryOff + seq.IKIndex;
                int numRules = Math.min(seq.numIK, 32);

                for (int r = 0; r < numRules; r++) {
                    int off = ikRuleDataAddr + r * IKRULE_SIZE;
                    if (off + IKRULE_SIZE > bufferLimit) break;

                    IKRule rule = new IKRule();
                    try {
                        rule.chain = buf.getInt(off);
                        rule.type = buf.getInt(off + 4);
                        rule.bone = buf.getInt(off + 8);
                        rule.slot = buf.getInt(off + 12);
                        rule.height = buf.getFloat(off + 16);
                        rule.radius = buf.getFloat(off + 20);
                        rule.floor = buf.getFloat(off + 24);
                        rule.pos[0] = buf.getFloat(off + 28);
                        rule.pos[1] = buf.getFloat(off + 32);
                        rule.pos[2] = buf.getFloat(off + 36);
                        rule.quat[0] = buf.getFloat(off + 40);
                        rule.quat[1] = buf.getFloat(off + 44);
                        rule.quat[2] = buf.getFloat(off + 48);
                        rule.quat[3] = buf.getFloat(off + 52);

                        int compressedErrorIdx = buf.getInt(off + 56);
                        if (compressedErrorIdx > 0) {
                            int errOff = off + compressedErrorIdx;
                            if (errOff + 24 <= bufferLimit) {
                                for (int s = 0; s < 6; s++) rule.errorScale[s] = buf.getFloat(errOff + s * 4);
                                for (int s = 0; s < 6; s++) rule.errorOffset[s] = buf.getShort(errOff + 24 + s * 2);
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[MdlParser] IKRule parse error at seq {} rule {}: {}", si, r, e.getMessage());
                        continue;
                    }

                    int attachNameOff = buf.getInt(off + 72);
                    if (attachNameOff > 0 && seqEntryOff + attachNameOff < bufferLimit) {
                        rule.attachment = readString(buf, seqEntryOff + attachNameOff, bufferLimit);
                    }
                    rules.add(rule);
                }
                result.sequenceIKRules.add(rules);
            }
        }

        public static void parseAutolayers(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.sequences.isEmpty()) return;
            int seqdescSize = result.seqdescSize;
            int localSeqIndex = result.header.localseqindex;
            if (localSeqIndex <= 0) return;

            for (int si = 0; si < result.sequences.size(); si++) {
                java.util.List<Autolayer> layers = new java.util.ArrayList<>();
                int seqEntryOff = localSeqIndex + si * seqdescSize;
                int numAutolayers = buf.getInt(seqEntryOff + 120);
                int autolayerIndex = buf.getInt(seqEntryOff + 124);
                if (numAutolayers > 0 && numAutolayers <= 16 && autolayerIndex > 0) {
                    int dataAddr = seqEntryOff + autolayerIndex;
                    if (dataAddr + numAutolayers * 24 <= bufferLimit) {
                        for (int a = 0; a < numAutolayers; a++) {
                            int aOff = dataAddr + a * 24;
                            Autolayer al = new Autolayer();
                            al.sequence = buf.getShort(aOff);
                            al.pose = buf.getShort(aOff + 2);
                            al.flags = buf.getInt(aOff + 4);
                            al.start = buf.getFloat(aOff + 8);
                            al.peak = buf.getFloat(aOff + 12);
                            al.tail = buf.getFloat(aOff + 16);
                            al.end = buf.getFloat(aOff + 20);
                            layers.add(al);
                        }
                    }
                }
                result.sequenceAutolayers.add(layers);
            }
        }

        public static void parseActivityModifiers(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.sequences.isEmpty()) return;
            int seqdescSize = result.seqdescSize;
            int localSeqIndex = result.header.localseqindex;
            if (localSeqIndex <= 0) return;

            for (int si = 0; si < result.sequences.size(); si++) {
                java.util.List<ActivityModifier> mods = new java.util.ArrayList<>();
                int seqEntryOff = localSeqIndex + si * seqdescSize;
                int amIdxOff = seqEntryOff + seqdescSize - 20;
                if (amIdxOff + 8 <= bufferLimit) {
                    try {
                        int numMods = buf.getInt(amIdxOff);
                        int modIdx = buf.getInt(amIdxOff + 4);
                        if (numMods > 0 && numMods <= 32 && modIdx > 0) {
                            int modAddr = seqEntryOff + modIdx;
                            if (modAddr + numMods * 8 <= bufferLimit) {
                                for (int a = 0; a < numMods; a++) {
                                    int aOff = modAddr + a * 8;
                                    int nameOff = buf.getInt(aOff);
                                    ActivityModifier am = new ActivityModifier();
                                    if (nameOff > 0) {
                                        am.name = readString(buf, seqEntryOff + nameOff, bufferLimit);
                                    }
                                    mods.add(am);
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.debug("[MdlParser] ActivityModifier parse error at seq {}: {}", si, e.getMessage());
                    }
                }
                result.sequenceActivityModifiers.add(mods);
            }
        }

        public static void parseMovements(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.sequences.isEmpty()) return;
            int seqdescSize = result.seqdescSize;
            int localSeqIndex = result.header.localseqindex;
            if (localSeqIndex <= 0) return;

            for (int si = 0; si < result.sequences.size(); si++) {
                java.util.List<Movement> movements = new java.util.ArrayList<>();
                MdlDataTypes.SeqDesc seq = result.sequences.get(si);
                if (seq.numblends > 0 && seq.animindex > 0) {
                    int seqEntryOff = localSeqIndex + si * seqdescSize;
                    int animDescOff = seqEntryOff + seq.animindex;
                    if (animDescOff + 64 <= bufferLimit) {
                        int numMovements = buf.getInt(animDescOff + 24);
                        int movementIndex = buf.getInt(animDescOff + 28);
                        if (numMovements > 0 && numMovements <= 64 && movementIndex > 0) {
                            int movAddr = animDescOff + movementIndex;
                            if (movAddr + numMovements * 44 <= bufferLimit) {
                                for (int m = 0; m < numMovements; m++) {
                                    int mOff = movAddr + m * 44;
                                    Movement mv = new Movement();
                                    mv.endframe = buf.getInt(mOff);
                                    mv.motionflags = buf.getInt(mOff + 4);
                                    mv.v0 = buf.getFloat(mOff + 8);
                                    mv.v1 = buf.getFloat(mOff + 12);
                                    mv.angle = buf.getFloat(mOff + 16);
                                    mv.vector[0] = buf.getFloat(mOff + 20);
                                    mv.vector[1] = buf.getFloat(mOff + 24);
                                    mv.vector[2] = buf.getFloat(mOff + 28);
                                    mv.position[0] = buf.getFloat(mOff + 32);
                                    mv.position[1] = buf.getFloat(mOff + 36);
                                    mv.position[2] = buf.getFloat(mOff + 40);
                                    movements.add(mv);
                                }
                            }
                        }
                    }
                }
                result.sequenceMovements.add(movements);
            }
        }

        public static void parseLocalHierarchies(java.nio.ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
            if (result.localAnims.isEmpty()) return;
            int localAnimBase = result.header.localanimindex;
            if (localAnimBase <= 0) return;

            int localAnimSize = 64;
            for (int ai = 0; ai < result.localAnims.size(); ai++) {
                int animOff = localAnimBase + ai * localAnimSize;
                if (animOff + localAnimSize > bufferLimit) continue;
                int numHier = buf.getInt(animOff + 48);
                int hierIdx = buf.getInt(animOff + 52);
                if (numHier > 0 && numHier <= 16 && hierIdx > 0) {
                    int hierOff = animOff + hierIdx;
                    if (hierOff + numHier * 32 <= bufferLimit) {
                        for (int h = 0; h < numHier; h++) {
                            int hOff = hierOff + h * 32;
                            LocalHierarchy lh = new LocalHierarchy();
                            lh.bone = buf.getInt(hOff);
                            lh.newParent = buf.getInt(hOff + 4);
                            lh.start = buf.getFloat(hOff + 8);
                            lh.peak = buf.getFloat(hOff + 12);
                            lh.tail = buf.getFloat(hOff + 16);
                            lh.end = buf.getFloat(hOff + 20);
                            lh.startFrame = buf.getInt(hOff + 24);
                            result.localHierarchies.add(lh);
                        }
                    }
                }
            }
        }

        private static String readString(java.nio.ByteBuffer buf, int offset, int bufferLimit) {
            if (offset <= 0 || offset >= bufferLimit) return "";
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
                return new String(baos.toByteArray(), 0, baos.size(), java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                buf.position(savedPos);
            }
        }
    }
}
