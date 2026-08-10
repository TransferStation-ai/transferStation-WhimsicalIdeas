package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the extended native PHY serialization contract: Java round-trips the
 * byte stream produced by nativeParsePhySerialized and reads back the new
 * per-solid properties, ragdoll constraints, and editparams segments.
 */
class PhyConstraintContractTest {

    @Test
    void deserializeExtendedSegments() {
        // Build a serialized blob matching the C++ ByteWriter layout in
        // native_core_bridge.cpp nativeParsePhySerialized (post-extension).
        // Structure (LE):
        //   MAGIC(4)=0x574E5057, size, id(str), solidCount, checksum,
        //   solidCount, [per solid: index, name, hulls...], then:
        //   Segment A: propCount + [parent, mass, surfaceprop(str), damping,
        //     rotdamping, inertia, volume] * propCount
        //   Segment B: constraintCount + [parentIndex, childIndex, parentName,
        //     childName, 9 floats, 3 pivot floats] * constraintCount
        //   Segment C: rootName(str), totalMass(float)
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(4096)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);

        buf.putInt(0x574E5057);
        buf.putInt(100);
        putString(buf, "VPHY");
        buf.putInt(1);
        buf.putInt(0x99);

        // solids block (existing schema)
        buf.putInt(1);
        buf.putInt(0);                 // solid.index
        putString(buf, "ValveBiped.Bip01_Head");
        buf.putInt(0);                 // hull count

        // Segment A
        buf.putInt(1);
        buf.putInt(3);                 // parent
        buf.putFloat(4.5f);            // mass
        putString(buf, "flesh");       // surfaceprop
        buf.putFloat(0.1f);            // damping
        buf.putFloat(0.2f);            // rotdamping
        buf.putFloat(1.0f);            // inertia
        buf.putFloat(2.0f);            // volume

        // Segment B
        buf.putInt(1);
        buf.putInt(3);                 // parentIndex
        buf.putInt(0);                 // childIndex
        putString(buf, "ValveBiped.Bip01_Neck1");
        putString(buf, "ValveBiped.Bip01_Head");
        buf.putFloat(-1.2f); buf.putFloat(1.2f); buf.putFloat(0.1f);
        buf.putFloat(-0.5f); buf.putFloat(0.5f); buf.putFloat(0.2f);
        buf.putFloat(-0.3f); buf.putFloat(0.3f); buf.putFloat(0.3f);
        buf.putFloat(0.0f); buf.putFloat(1.5f); buf.putFloat(0.0f);

        // Segment C
        putString(buf, "ValveBiped.Bip01");
        buf.putFloat(80.0f);

        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, buf.position());

        PhyParser.ParsedPhy phy = WindowsNativeModelParserStrategy.deserializeParsedPhy(data);
        assertNotNull(phy);
        assertEquals(1, phy.solids.size());
        PhyParser.PhySolid solid = phy.solids.get(0);
        assertEquals(3, solid.parent);
        assertEquals(4.5f, solid.mass, 0.001f);
        assertEquals("flesh", solid.surfaceprop);
        assertEquals(0.2f, solid.rotdamping, 0.001f);

        assertEquals(1, phy.ragdollConstraints.size());
        PhyParser.PhyConstraint c = phy.ragdollConstraints.get(0);
        assertEquals("ValveBiped.Bip01_Head", c.childName);
        assertEquals(1.2f, c.xmax, 0.001f);
        assertEquals(1.5f, c.pivotY, 0.001f);

        assertEquals("ValveBiped.Bip01", phy.rootName);
        assertEquals(80.0f, phy.totalMass, 0.001f);
    }

    private static void putString(java.nio.ByteBuffer buf, String s) {
        buf.putShort((short) s.length());
        buf.put(s.getBytes(StandardCharsets.UTF_8));
    }
}
