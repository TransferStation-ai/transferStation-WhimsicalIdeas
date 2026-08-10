package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhyParserTest {

    /**
     * Minimal .phy byte blob: 16-byte header + 4 binary solid sections (each
     * 28 bytes, zero surface size so convex parsing is skipped) + a "solid"
     * KV text section carrying solid properties and a ragdollconstraint block.
     */
    private byte[] buildPhyWithConstraints() {
        String kv =
            "solid {\n" +
            "\t\"index\" \"0\"\n" +
            "\t\"name\" \"ValveBiped.Bip01_Head\"\n" +
            "\t\"parent\" \"3\"\n" +
            "\t\"mass\" \"4.50\"\n" +
            "\t\"surfaceprop\" \"flesh\"\n" +
            "\t\"damping\" \"0.10\"\n" +
            "\t\"rotdamping\" \"0.20\"\n" +
            "\t\"inertia\" \"1.0\"\n" +
            "\t\"volume\" \"2.0\"\n" +
            "}\n" +
            "solid\n{\n" +
            "\t\"index\" \"3\"\n" +
            "\t\"name\" \"ValveBiped.Bip01_Neck1\"\n" +
            "}\n" +
            "ragdollconstraint\n{\n" +
            "\t\"parent\" \"3\"\n" +
            "\t\"child\" \"0\"\n" +
            "\t\"xmin\" \"-1.2\"\n" +
            "\t\"xmax\" \"1.2\"\n" +
            "\t\"xfriction\" \"0.1\"\n" +
            "\t\"ymin\" \"-0.5\"\n" +
            "\t\"ymax\" \"0.5\"\n" +
            "\t\"yfriction\" \"0.2\"\n" +
            "\t\"zmin\" \"-0.3\"\n" +
            "\t\"zmax\" \"0.3\"\n" +
            "\t\"zfriction\" \"0.3\"\n" +
            "}\n" +
            "editparams\n{\n" +
            "\t\"rootname\" \"ValveBiped.Bip01\"\n" +
            "\t\"totalmass\" \"80.0\"\n" +
            "}\n";
        byte[] kvBytes = kv.getBytes(StandardCharsets.UTF_8);

        // Header: size, id("VPHY"), solidCount=4, checksum.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 + 4 * 28 + kvBytes.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0);                    // size (patched below)
        buf.put("VPHY".getBytes(StandardCharsets.UTF_8));
        buf.putInt(4);                    // solidCount
        buf.putInt(0x1234);               // checksum

        // 4 binary solid sections: [size=24][VPHY][ver=24][mType=2][surfaceSize=0][pad].
        // Each section occupies 4 (size) + 24 (content) = 28 bytes on disk; the parser
        // advances by sectionOrigin + 4 + sectionSize.
        for (int i = 0; i < 4; i++) {
            buf.putInt(24);                       // section size
            buf.put("VPHY".getBytes(StandardCharsets.UTF_8));
            buf.putShort((short) 24);             // version
            buf.putShort((short) 2);              // model type
            buf.putInt(0);                        // surfaceSize (skip convex hulls)
            buf.put(new byte[12]);                // padding to 24 content bytes
        }

        buf.put(kvBytes);
        // Fix up size field = bytes after the 16-byte header.
        buf.putInt(0, buf.position() - 16);
        return buf.array();
    }

    @Test
    void parsesSolidProperties() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertTrue(phy.valid);
        assertEquals(4, phy.solids.size());

        PhyParser.PhySolid head = phy.solids.get(0);
        assertEquals("ValveBiped.Bip01_Head", head.name);
        assertEquals(3, head.parent);
        assertEquals(4.5f, head.mass, 0.001f);
        assertEquals("flesh", head.surfaceprop);
        assertEquals(0.1f, head.damping, 0.001f);
        assertEquals(0.2f, head.rotdamping, 0.001f);
        assertEquals(1.0f, head.inertia, 0.001f);
        assertEquals(2.0f, head.volume, 0.001f);
    }

    @Test
    void parsesRagdollConstraints() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertEquals(1, phy.ragdollConstraints.size());

        PhyParser.PhyConstraint c = phy.ragdollConstraints.get(0);
        assertEquals(3, c.parentIndex);
        assertEquals(0, c.childIndex);
        assertEquals("ValveBiped.Bip01_Neck1", c.parentName);
        assertEquals("ValveBiped.Bip01_Head", c.childName);
        assertEquals(-1.2f, c.xmin, 0.001f);
        assertEquals(1.2f, c.xmax, 0.001f);
        assertEquals(0.1f, c.xfriction, 0.001f);
        assertEquals(0.3f, c.zfriction, 0.001f);
    }

    @Test
    void parsesEditParams() {
        PhyParser.ParsedPhy phy = PhyParser.parse(buildPhyWithConstraints());
        assertEquals("ValveBiped.Bip01", phy.rootName);
        assertEquals(80.0f, phy.totalMass, 0.001f);
    }
}
