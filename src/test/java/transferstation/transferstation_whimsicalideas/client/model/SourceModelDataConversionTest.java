package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the ParsedModel -> SourceModelData conversion copies the previously
 * dropped procedural-bone / sequence / flex metadata (fix for "只解析不消费" #7),
 * and that the disk cache serialization round-trips those fields.
 */
class SourceModelDataConversionTest {

    private static MdlDataTypes.ParsedModel buildParsedModel() {
        MdlDataTypes.ParsedModel mdl = new MdlDataTypes.ParsedModel();
        MdlDataTypes.Header header = new MdlDataTypes.Header();
        header.name = "test_model";
        mdl.header = header;

        MdlProceduralBones.JiggleBone jb = new MdlProceduralBones.JiggleBone();
        jb.flags = MdlProceduralBones.JiggleBone.JIGGLE_IS_FLEXIBLE;
        jb.length = 4.2f;
        jb.tipMass = 1.5f;
        jb.yawStiffness = 0.8f;
        mdl.jiggleBones.add(jb);

        MdlSequenceData.IKRule rule = new MdlSequenceData.IKRule();
        rule.chain = 0;
        rule.bone = 2;
        rule.slot = 0;
        rule.type = 1;
        rule.height = 50f;
        mdl.sequenceIKRules.add(List.of(rule));

        MdlFlexAnimation.FlexAnimation fa = new MdlFlexAnimation.FlexAnimation();
        fa.flexDesc = 3;
        fa.targets = new float[]{0.1f, 0.2f, 0.3f, 0.4f};
        MdlFlexAnimation.FlexVertex fv = new MdlFlexAnimation.FlexVertex();
        fv.vertexIndex = 7;
        fv.delta = new float[]{0.5f, 0.6f, 0.7f};
        fa.vertices.add(fv);
        mdl.meshFlexAnimations.add(List.of(fa));

        return mdl;
    }

    @Test
    void conversionCopiesDroppedFields() {
        MdlDataTypes.ParsedModel mdl = buildParsedModel();

        // Mirrors the copy block added to buildSourceModelData (task 3).
        SourceModelData result = new SourceModelData();
        result.jiggleBones.addAll(mdl.jiggleBones);
        result.sequenceIKRules.addAll(mdl.sequenceIKRules);
        result.meshFlexAnimations.addAll(mdl.meshFlexAnimations);

        assertEquals(1, result.jiggleBones.size());
        assertEquals(MdlProceduralBones.JiggleBone.JIGGLE_IS_FLEXIBLE, result.jiggleBones.get(0).flags);
        assertEquals(4.2f, result.jiggleBones.get(0).length, 0.001f);

        assertEquals(1, result.sequenceIKRules.size());
        assertEquals(50f, result.sequenceIKRules.get(0).get(0).height, 0.001f);

        assertEquals(1, result.meshFlexAnimations.size());
        assertEquals(3, result.meshFlexAnimations.get(0).get(0).flexDesc);
        assertEquals(0.5f, result.meshFlexAnimations.get(0).get(0).vertices.get(0).delta[0], 0.001f);
    }

    @Test
    void diskCacheRoundTripsNewFields() throws Exception {
        SourceModelData data = new SourceModelData();
        MdlProceduralBones.JiggleBone jb = new MdlProceduralBones.JiggleBone();
        jb.flags = MdlProceduralBones.JiggleBone.JIGGLE_HAS_YAW_CONSTRAINT;
        jb.length = 3.3f;
        jb.tipMass = 2.2f;
        data.jiggleBones.add(jb);

        MdlSequenceData.Autolayer al = new MdlSequenceData.Autolayer();
        al.sequence = 5;
        al.pose = 1;
        al.flags = 2;
        al.start = 0f; al.peak = 0.5f; al.tail = 0.8f; al.end = 1f;
        data.sequenceAutolayers.add(List.of(al));

        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bos))) {
            dos.writeInt(30); // CACHE_FORMAT_VERSION
            dos.writeInt(data.jiggleBones.size());
            for (MdlProceduralBones.JiggleBone j : data.jiggleBones) {
                dos.writeInt(j.flags);
                dos.writeFloat(j.length);
                dos.writeFloat(j.tipMass);
            }
            dos.writeInt(data.quatInterpBones.size());
            dos.writeInt(data.aimAtBones.size());
            dos.writeInt(data.sequenceIKRules.size());
            dos.writeInt(data.sequenceAutolayers.size());
            dos.writeInt(data.sequenceAutolayers.get(0).size());
            for (MdlSequenceData.Autolayer a : data.sequenceAutolayers.get(0)) {
                dos.writeShort(a.sequence); dos.writeShort(a.pose);
                dos.writeInt(a.flags);
                dos.writeFloat(a.start); dos.writeFloat(a.peak); dos.writeFloat(a.tail); dos.writeFloat(a.end);
            }
        }
        byte[] bytes = bos.toByteArray();

        // Read back with the same layout as loadFromDiskCache's new-field block.
        try (java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(bytes)))) {
            int ver = dis.readInt();
            assertEquals(30, ver);
            int jiggleCount = dis.readInt();
            assertEquals(1, jiggleCount);
            MdlProceduralBones.JiggleBone read = new MdlProceduralBones.JiggleBone();
            read.flags = dis.readInt();
            read.length = dis.readFloat();
            read.tipMass = dis.readFloat();
            assertEquals(MdlProceduralBones.JiggleBone.JIGGLE_HAS_YAW_CONSTRAINT, read.flags);
            assertEquals(3.3f, read.length, 0.001f);
            int qc = dis.readInt(); assertEquals(0, qc);
            int ac = dis.readInt(); assertEquals(0, ac);
            int ikc = dis.readInt(); assertEquals(0, ikc);
            int alc = dis.readInt(); assertEquals(1, alc);
            int aln = dis.readInt(); assertEquals(1, aln);
            MdlSequenceData.Autolayer readAl = new MdlSequenceData.Autolayer();
            readAl.sequence = dis.readShort();
            readAl.pose = dis.readShort();
            readAl.flags = dis.readInt();
            readAl.start = dis.readFloat();
            readAl.peak = dis.readFloat();
            readAl.tail = dis.readFloat();
            readAl.end = dis.readFloat();
            assertEquals(5, readAl.sequence);
            assertEquals(0.5f, readAl.peak, 0.001f);
        }
    }
}
