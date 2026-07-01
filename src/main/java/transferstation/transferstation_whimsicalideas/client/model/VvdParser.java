package transferstation.transferstation_whimsicalideas.client.model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class VvdParser {

    public static final int STUDIO_VERTEX_SIZE = 48;

    private static final int MAX_FIXUPS = 65536;
    private static final int MAX_VERTICES = 1_000_000;
    private static final int MAX_FILE_SIZE = 512 * 1024 * 1024;
    private static final int FIXUP_SIZE = 12;
    private static final int VVD_VERTEX_RECORD_SIZE = 48;

    public static class VvdHeader {
        public int id;
        public int version;
        public int checksum;
        public int numLODs;
        public int[] numLODVertices;
        public int numFixups;
        public int fixupTableStart;
        public int vertexDataStart;
        public int tangentDataStart;
    }

    public static class VvdFixup {
        public int lodIndex;
        public int sourceVertexID;
        public int numVertexes;
    }

    public static class BoneWeight {
        public float[] weight;
        public int[] bone;
        public int numbones;

        public BoneWeight() {
            weight = new float[3];
            bone = new int[3];
        }
    }

    public static class StudioVertexExt {
        public float x, y, z;
        public float nx, ny, nz;
        public float u, v;
        public BoneWeight boneWeight;

        public float bone0Weight() { return boneWeight.weight[0]; }
        public int bone0Index() { return boneWeight.bone[0]; }
        public float bone1Weight() { return boneWeight.weight[1]; }
        public int bone1Index() { return boneWeight.bone[1]; }
        public float bone2Weight() { return boneWeight.weight[2]; }
        public int bone2Index() { return boneWeight.bone[2]; }
    }

    public static class ParsedVvd {
        public VvdHeader header;
        public List<VvdFixup> fixups = new ArrayList<>();
        public List<StudioVertexExt> vertices = new ArrayList<>();
        public List<List<StudioVertexExt>> lodVertices = new ArrayList<>();

        public List<StudioVertexExt> getVerticesForLod(int lod) {
            if (lod <= 0 || lod > lodVertices.size()) {
                return vertices;
            }
            return lodVertices.get(lod - 1);
        }
    }

    public static ParsedVvd parse(byte[] data) {
        if (data.length > MAX_FILE_SIZE) {
            throw new RuntimeException("VVD file too large: " + data.length + " bytes (max " + MAX_FILE_SIZE + ")");
        }

        if (data.length < 64) {
            throw new RuntimeException("VVD file too small: " + data.length + " bytes (min 64)");
        }

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int bufferLimit = buf.limit();

        ParsedVvd result = new ParsedVvd();
        result.header = parseHeader(buf, bufferLimit);

        int expectedMagic = 0x56534449; // "IDSV" in little-endian
        if (result.header.id != expectedMagic) {
            throw new RuntimeException("Not a valid VVD file (bad magic: expected 0x" +
                    Integer.toHexString(expectedMagic) + " (IDSV), got 0x" +
                    Integer.toHexString(result.header.id) + ")");
        }

        try {
            parseFixups(buf, result, bufferLimit);
        } catch (Exception e) {
            System.out.println("[VvdParser] Fixup parse error (non-fatal): " + e.getMessage());
        }

        List<StudioVertexExt> rawVertices;
        try {
            rawVertices = readRawVertices(buf, result, bufferLimit);
        } catch (Exception e) {
            System.out.println("[VvdParser] Vertex read error (non-fatal): " + e.getMessage());
            rawVertices = new ArrayList<>();
        }

        try {
            applyFixups(result, rawVertices);
        } catch (Exception e) {
            System.out.println("[VvdParser] Fixup apply error (non-fatal): " + e.getMessage());
            if (result.vertices.isEmpty()) {
                result.vertices = rawVertices;
            }
        }

        return result;
    }

    private static void assertInBounds(int offset, int size, int bufferLimit, String fieldName) {
        if (offset < 0 || offset > bufferLimit - size) {
            long endPos = (long) offset + size;
            throw new RuntimeException(String.format(
                    "VVD parse error: %s at offset %d (size %d) exceeds buffer limit %d (would end at %d)",
                    fieldName, offset, size, bufferLimit, endPos));
        }
    }

    private static int sanitizeCount(int count, int max, String fieldName) {
        if (count < 0 || count > max) {
            throw new RuntimeException(String.format(
                    "VVD parse error: %s count %d exceeds maximum %d", fieldName, count, max));
        }
        return count;
    }

    private static VvdHeader parseHeader(ByteBuffer buf, int bufferLimit) {
        assertInBounds(buf.position(), 64, bufferLimit, "header");

        VvdHeader h = new VvdHeader();
        h.id = buf.getInt();
        h.version = buf.getInt();
        h.checksum = buf.getInt();
        h.numLODs = sanitizeCount(buf.getInt(), 8, "numLODs");
        h.numLODVertices = new int[8];
        for (int i = 0; i < 8; i++) {
            h.numLODVertices[i] = buf.getInt();
        }
        h.numFixups = buf.getInt();
        h.fixupTableStart = buf.getInt();
        h.vertexDataStart = buf.getInt();
        h.tangentDataStart = buf.getInt();
        return h;
    }

    private static void parseFixups(ByteBuffer buf, ParsedVvd result, int bufferLimit) {
        int numFixups = sanitizeCount(result.header.numFixups, MAX_FIXUPS, "numFixups");
        if (numFixups == 0) return;

        assertInBounds(result.header.fixupTableStart, numFixups * FIXUP_SIZE, bufferLimit, "fixupTableStart");
        buf.position(result.header.fixupTableStart);
        for (int i = 0; i < numFixups; i++) {
            VvdFixup f = new VvdFixup();
            f.lodIndex = buf.getInt();
            f.sourceVertexID = buf.getInt();
            f.numVertexes = buf.getInt();
            result.fixups.add(f);
        }
    }

    private static List<StudioVertexExt> readRawVertices(ByteBuffer buf, ParsedVvd result, int bufferLimit) {
        int numVertices = sanitizeCount(result.header.numLODVertices[0], MAX_VERTICES, "numLODVertices[0]");

        assertInBounds(result.header.vertexDataStart, numVertices * VVD_VERTEX_RECORD_SIZE, bufferLimit, "vertexDataStart");
        buf.position(result.header.vertexDataStart);

        List<StudioVertexExt> raw = new ArrayList<>(numVertices);
        for (int i = 0; i < numVertices; i++) {
            StudioVertexExt v = new StudioVertexExt();
            BoneWeight bw = new BoneWeight();

            bw.weight[0] = buf.getFloat();
            bw.weight[1] = buf.getFloat();
            bw.weight[2] = buf.getFloat();
            bw.bone[0] = buf.get() & 0xFF;
            bw.bone[1] = buf.get() & 0xFF;
            bw.bone[2] = buf.get() & 0xFF;
            bw.numbones = buf.get() & 0xFF;

            v.x = buf.getFloat();
            v.y = buf.getFloat();
            v.z = buf.getFloat();
            v.nx = buf.getFloat();
            v.ny = buf.getFloat();
            v.nz = buf.getFloat();
            v.u = buf.getFloat();
            v.v = buf.getFloat();

            v.boneWeight = bw;
            raw.add(v);
        }
        return raw;
    }

    private static void applyFixups(ParsedVvd result, List<StudioVertexExt> rawVertices) {
        if (result.fixups.isEmpty()) {
            result.vertices.addAll(rawVertices);
            return;
        }

        int maxLOD = 4;
        for (int lod = 0; lod < maxLOD; lod++) {
            List<StudioVertexExt> lodVerts = new ArrayList<>();
            for (VvdFixup fixup : result.fixups) {
                if (fixup.lodIndex != lod) continue;
                if (fixup.sourceVertexID < 0 || fixup.numVertexes <= 0) continue;
                if (fixup.sourceVertexID >= rawVertices.size()) continue;
                int end = Math.min(fixup.sourceVertexID + fixup.numVertexes, rawVertices.size());
                for (int i = fixup.sourceVertexID; i < end; i++) {
                    lodVerts.add(rawVertices.get(i));
                }
            }
            if (lod == 0) {
                if (lodVerts.isEmpty()) {
                    result.vertices.addAll(rawVertices);
                } else {
                    result.vertices.addAll(lodVerts);
                }
            }
            if (!lodVerts.isEmpty()) {
                result.lodVertices.add(lodVerts);
            }
        }

        if (result.vertices.isEmpty()) {
            result.vertices.addAll(rawVertices);
        }
    }
}