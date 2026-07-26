package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class VvdParserTest {

    @Test
    void parseTangents() {
        ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x56534449);
        buf.putInt(4);
        buf.putInt(0);
        buf.putInt(1);
        buf.putInt(2);
        for (int i = 1; i < 8; i++) buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(64);
        buf.putInt(160);

        for (int v = 0; v < 2; v++) {
            buf.putFloat(0.5f); buf.putFloat(0.5f); buf.putFloat(0.5f);
            buf.put((byte)0); buf.put((byte)1); buf.put((byte)2);
            buf.put((byte)3);
            buf.putFloat(1); buf.putFloat(2); buf.putFloat(3);
            buf.putFloat(0); buf.putFloat(0); buf.putFloat(1);
            buf.putFloat(0.5f); buf.putFloat(0.5f);
        }

        buf.putFloat(1); buf.putFloat(0); buf.putFloat(0); buf.putFloat(1);
        buf.putFloat(0); buf.putFloat(1); buf.putFloat(0); buf.putFloat(-1);

        byte[] data = new byte[buf.position()];
        buf.rewind(); buf.get(data);

        VvdParser.ParsedVvd vvd = VvdParser.parse(data);
        assertNotNull(vvd);
        assertEquals(2, vvd.vertices.size());

        List<VvdParser.VvdTangent> tangents = VvdParser.parseTangents(data, vvd);
        assertEquals(2, tangents.size());

        assertEquals(1f, tangents.get(0).x, 0.001f);
        assertEquals(0f, tangents.get(0).y, 0.001f);
        assertEquals(0f, tangents.get(0).z, 0.001f);
        assertEquals(1f, tangents.get(0).w, 0.001f);

        assertEquals(0f, tangents.get(1).x, 0.001f);
        assertEquals(1f, tangents.get(1).y, 0.001f);
        assertEquals(0f, tangents.get(1).z, 0.001f);
        assertEquals(-1f, tangents.get(1).w, 0.001f);
    }

    @Test
    void parseTangents_noTangentData() {
        ByteBuffer buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x56534449);
        buf.putInt(4);
        buf.putInt(0);
        buf.putInt(1);
        buf.putInt(1);
        for (int i = 1; i < 8; i++) buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(64);
        buf.putInt(0);

        buf.putFloat(1); buf.putFloat(0); buf.putFloat(0);
        buf.put((byte)0); buf.put((byte)0); buf.put((byte)0);
        buf.put((byte)1);
        buf.putFloat(0); buf.putFloat(0); buf.putFloat(0);
        buf.putFloat(0); buf.putFloat(0); buf.putFloat(1);
        buf.putFloat(0); buf.putFloat(0);

        byte[] data = new byte[buf.position()];
        buf.rewind(); buf.get(data);

        VvdParser.ParsedVvd vvd = VvdParser.parse(data);
        List<VvdParser.VvdTangent> tangents = VvdParser.parseTangents(data, vvd);
        assertTrue(tangents.isEmpty());
    }
}
