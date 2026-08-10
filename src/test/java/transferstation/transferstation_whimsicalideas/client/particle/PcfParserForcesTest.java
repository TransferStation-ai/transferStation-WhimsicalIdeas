package transferstation.transferstation_whimsicalideas.client.particle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcfParserForcesTest {

    @Test
    void parsesForceDirectionArray() throws Exception {
        PcfParticleSystemDef defs = PcfParser.parse(forcePcfBytes());
        PcfParticleSystemDef.ForceDef f = defs.systemDefinitions.get(0).forces.get(0);
        assertEquals("gravity", f.type);
        assertEquals(100f, f.magnitude, 0.001f);
        assertEquals(0f, f.direction[0], 0.001f);
        assertEquals(-1f, f.direction[1], 0.001f);
        assertEquals(0f, f.direction[2], 0.001f);
    }

    @Test
    void parsesControlPointBasedFlags() throws Exception {
        PcfParticleSystemDef defs = PcfParser.parse(controlPointPcfBytes());
        PcfParticleSystemDef.ForceDef f = defs.systemDefinitions.get(0).forces.get(0);
        assertTrue(f.controlPointBased, "m_bControlPointBased=1 should set controlPointBased");
        assertEquals(5, f.controlPoint);
    }

    // --- byte stream construction helpers ---

    private static byte[] forcePcfBytes() throws IOException {
        var out = pcfHeader();
        out.write(0x08); writeString(out, "m_particleSystemDefinition");
        out.write(0x01); writeString(out, "m_name"); writeString(out, "test_system");
        out.write(0x0A); writeString(out, "_forces");
        out.write(0x08); writeString(out, ""); // force object (empty name)
        out.write(0x01); writeString(out, "m_nForceType"); writeString(out, "gravity");
        out.write(0x03); writeString(out, "m_flMagnitude"); writeFloat(out, 100f);
        out.write(0x0A); writeString(out, "m_vDirection");
        out.write(0x03); writeString(out, ""); writeFloat(out, 0f);
        out.write(0x03); writeString(out, ""); writeFloat(out, -1f);
        out.write(0x03); writeString(out, ""); writeFloat(out, 0f);
        out.write(0x0B); // ARRAY_CLOSE
        out.write(0x09); // OBJECT_CLOSE (force)
        out.write(0x0B); // ARRAY_CLOSE (_forces)
        out.write(0x09); // OBJECT_CLOSE (m_particleSystemDefinition)
        return out.toByteArray();
    }

    private static byte[] controlPointPcfBytes() throws IOException {
        var out = pcfHeader();
        out.write(0x08); writeString(out, "m_particleSystemDefinition");
        out.write(0x01); writeString(out, "m_name"); writeString(out, "test_system");
        out.write(0x0A); writeString(out, "_forces");
        out.write(0x08); writeString(out, ""); // force object
        out.write(0x01); writeString(out, "m_nForceType"); writeString(out, "attractor");
        out.write(0x02); writeString(out, "m_bControlPointBased"); writeInt(out, 1);
        out.write(0x02); writeString(out, "m_nControlPoint"); writeInt(out, 5);
        out.write(0x09); // OBJECT_CLOSE (force)
        out.write(0x0B); // ARRAY_CLOSE (_forces)
        out.write(0x09); // OBJECT_CLOSE (m_particleSystemDefinition)
        return out.toByteArray();
    }

    private static ByteArrayOutputStream pcfHeader() throws IOException {
        var out = new ByteArrayOutputStream();
        out.write('P'); out.write('C'); out.write('F'); out.write('F');
        writeInt(out, 2); // version
        writeInt(out, 0); // padding
        return out;
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.write(0);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    private static void writeFloat(ByteArrayOutputStream out, float v) throws IOException {
        writeInt(out, Float.floatToIntBits(v));
    }
}
