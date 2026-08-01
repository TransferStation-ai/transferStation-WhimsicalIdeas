package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PhyParser {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_SOLIDS = 256;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;

    public static class ParsedPhy {
        public int size;
        public String id;
        public int solidCount;
        public int checksum;
        public List<PhySolid> solids = new ArrayList<>();
        public boolean valid;
    }

    public static class PhySolid {
        public int index;
        public String name;
        public List<PhyConvexHull> convexHulls = new ArrayList<>();
    }

    public static class PhyConvexHull {
        public int vertexOffset;
        public int boneIndex;
        public int flags;
        public int triangleCount;
        public List<PhyTriangle> triangles = new ArrayList<>();
        public List<PhyVertex> vertices = new ArrayList<>();
    }

    public static class PhyTriangle {
        public int vertexIndex;
        public int v1, v2, v3;
    }

    public static class PhyVertex {
        public float x, y, z;
    }

    public static ParsedPhy parse(byte[] data) {
        ParsedPhy result = new ParsedPhy();
        result.valid = false;

        if (data.length > MAX_FILE_SIZE) {
            LOGGER.warn("[PhyParser] PHY file too large: {} bytes, skipping", data.length);
            return result;
        }
        if (data.length < 16) {
            LOGGER.warn("[PhyParser] PHY file too small: {} bytes, skipping", data.length);
            return result;
        }

        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int fileLen = buf.limit();

            result.size = buf.getInt();
            result.id = readFixedString(buf);
            result.solidCount = buf.getInt();
            result.checksum = buf.getInt();

            boolean altOffset = false;
                if (fileLen >= 20) {
                int altMagic = buf.getInt(16);
                if (altMagic == 0x59504856 || altMagic == 0x53594850) {
                    altOffset = true;
                    result.id = "VPHY";
                }
            }

            boolean tryParse = result.id.equals("VPHY") || result.id.equals("PHYS") ||
                (result.solidCount > 0 && result.solidCount <= MAX_SOLIDS);
            if (!tryParse || result.solidCount < 0 || result.solidCount > MAX_SOLIDS) {
                // An unrecognized/garbage PHY must NOT be reported as valid.
                LOGGER.warn("[PhyParser] Unrecognized or invalid PHY (id='{}', solidCount={}); marking invalid",
                    result.id, result.solidCount);
                result.valid = false;
                return result;
            }

            // Parse KeyValues text section for solid name mapping
            int kvStart = findKvStart(data);
            Map<Integer, String> solidNames = (kvStart > 0) ? parseSolidNames(data, kvStart, fileLen) : new HashMap<>();

            int binaryEnd = (kvStart > 0) ? kvStart : fileLen;
            int cursor = 16;

            for (int i = 0; i < result.solidCount; i++) {
                if (cursor + 28 > binaryEnd) break;

                int sectionOrigin = cursor;
                int sectionSize = buf.getInt(cursor);
                if (sectionSize <= 0 || 4 + sectionSize > binaryEnd - cursor) break;

                // Check for VPHY magic at cursor+4
                String vphysicsId = "";
                if (cursor + 8 <= binaryEnd) {
                    vphysicsId = readFixedString(buf, cursor + 4);
                }

                int surfaceSize = 0;
                if (vphysicsId.equals("VPHY") || vphysicsId.equals("PHYS")) {
                    int ver = buf.getShort(cursor + 8) & 0xFFFF;
                    int mType = buf.getShort(cursor + 10) & 0xFFFF;
                    surfaceSize = buf.getInt(cursor + 12);
                }

                PhySolid solid = new PhySolid();
                solid.index = i;
                solid.name = solidNames.getOrDefault(i, "solid_" + i);
                solid.convexHulls = new ArrayList<>();

                if (surfaceSize > 0 && surfaceSize < binaryEnd - cursor) {
                    int surfaceStart = cursor + 28;
                    if (surfaceStart + surfaceSize <= binaryEnd) {
                        parseSurfaceData(data, surfaceStart, surfaceSize, solid, i);
                    }
                }

                result.solids.add(solid);
                cursor = sectionOrigin + 4 + sectionSize;
            }

            result.valid = true;
        } catch (Exception e) {
            LOGGER.debug("[PhyParser] Parse error (non-fatal): {}", e.getMessage());
            result.valid = false;
        }
        return result;
    }

    private static int findKvStart(byte[] data) {
        // Strategy 1: Search the last 35% of the file for "solid {" (most reliable)
        int searchStart = Math.max(0, data.length - (int)(data.length * 0.35));
        for (int i = searchStart; i < data.length - 7; i++) {
            if (data[i] == 's' && data[i+1] == 'o' && data[i+2] == 'l' &&
                data[i+3] == 'i' && data[i+4] == 'd' && (data[i+5] == ' ' || data[i+5] == '\t')) {
                return i;
            }
        }
        // Strategy 2: Search the full file for "solid {"
        for (int i = 0; i < data.length - 8; i++) {
            if (data[i] == 's' && data[i+1] == 'o' && data[i+2] == 'l' &&
                data[i+3] == 'i' && data[i+4] == 'd' && (data[i+5] == ' ' || data[i+5] == '\t')) {
                return i;
            }
        }
        return 0;
    }

    private static Map<Integer, String> parseSolidNames(byte[] data, int kvStart, int kvEnd) {
        Map<Integer, String> names = new HashMap<>();
        String kvText = new String(data, kvStart, kvEnd - kvStart, StandardCharsets.UTF_8);
        int idx = 0;
        while (idx < kvText.length()) {
            int blockStart = kvText.indexOf("solid", idx);
            if (blockStart < 0) break;
            int braceOpen = kvText.indexOf('{', blockStart);
            if (braceOpen < 0) break;
            int braceClose = kvText.indexOf('}', braceOpen);
            if (braceClose < 0) break;
            String block = kvText.substring(braceOpen + 1, braceClose);
            Integer solidIndex = null;
            String solidName = null;
            int propIdx = 0;
            while (true) {
                int q1 = block.indexOf('"', propIdx); if (q1 < 0) break;
                int q2 = block.indexOf('"', q1 + 1); if (q2 < 0) break;
                int q3 = block.indexOf('"', q2 + 1); if (q3 < 0) break;
                int q4 = block.indexOf('"', q3 + 1); if (q4 < 0) break;
                String key = block.substring(q1 + 1, q2);
                String value = block.substring(q3 + 1, q4);
                if (key.equals("index")) try { solidIndex = Integer.parseInt(value); } catch (NumberFormatException e) {
                    throw new RuntimeException(e);
                }
                else if (key.equals("name")) solidName = value;
                propIdx = q4 + 1;
            }
            if (solidIndex != null && solidName != null) names.put(solidIndex, solidName);
            idx = braceClose + 1;
        }
        return names;
    }

    private static void parseSurfaceData(byte[] data, int absoluteStart, int size, PhySolid solid, int solidIdx) {
        if (size < 16) return;
        int end = absoluteStart + size;

        // Check for IVPS legacy format (wraps convex hull data in a node tree)
        boolean hasIvps = false;
        if (size >= 0x34) {
            int magic = readIntLE(data, absoluteStart + 0x30);
            hasIvps = (magic == 0x53505649); // "IVPS"
        }

        if (hasIvps) {
            // IVPS format: convex hull data is inside a legacy Havok-style tree.
            // Skip the legacy surface header (48 bytes) and IVPS header to find
            // the actual convex hull data. The IVPS tree leaf nodes reference
            // convex hulls. This requires a tree walk that is beyond the scope
            // of this parser. Fall back to heuristic scanning.
            parseConvexHullsHeuristic(data, absoluteStart, size, solid);
        } else {
            // Direct convex headers (modern VPHY without IVPS wrapper)
            parseConvexHullsDirect(data, absoluteStart, size, solid);
        }
    }

    private static void parseConvexHullsDirect(byte[] data, int absoluteStart, int size, PhySolid solid) {
        int end = absoluteStart + size;
        List<ConvexHdr> headers = new ArrayList<>();
        int pos = absoluteStart;

        while (pos + 16 <= end) {
            int relVertOffset = readIntLE(data, pos);
            int boneIdx = readIntLE(data, pos + 4);
            int flags = readIntLE(data, pos + 8);
            int triCount = readIntLE(data, pos + 12);

            if (relVertOffset < 0 || relVertOffset > size) break;
            if (triCount < 0 || triCount > 65536) break;
            if (relVertOffset <= (pos - absoluteStart) + 16 && relVertOffset > 0) break;

            ConvexHdr hdr = new ConvexHdr();
            hdr.vertexOffset = relVertOffset;
            hdr.boneIndex = boneIdx;
            hdr.flags = flags;
            hdr.triCount = triCount;
            hdr.headerStartRel = pos - absoluteStart;
            headers.add(hdr);
            pos += 16;
        }

        if (headers.isEmpty()) return;
        buildConvexHulls(data, absoluteStart, size, solid, headers);
    }

    private static void parseConvexHullsHeuristic(byte[] data, int absoluteStart, int size, PhySolid solid) {
        int end = absoluteStart + size;

        // Scan for convex hull headers (16 bytes each: vertexOffset, boneIdx, flags, triCount)
        // starting from a reasonable offset after the IVPS tree section.
        // The IVPS tree is typically < 256 bytes, so start scanning after that.
        int startScan = absoluteStart + Math.min(size / 4, 512);
        if (startScan >= end - 16) return;

        List<ConvexHdr> headers = new ArrayList<>();
        int pos = startScan;
        int maxHeaders = Math.min(128, size / 16);

        while (pos + 16 <= end && headers.size() < maxHeaders) {
            int relVertOffset = readIntLE(data, pos);
            int boneIdx = readIntLE(data, pos + 4);
            int flags = readIntLE(data, pos + 8);
            int triCount = readIntLE(data, pos + 12);

            if (relVertOffset <= 0 || relVertOffset >= size) { pos += 4; continue; }
            if (triCount <= 0 || triCount > 2048) { pos += 4; continue; }
            if (relVertOffset <= (pos - absoluteStart)) { pos += 4; continue; }

            int vertCheck = absoluteStart + relVertOffset;
            if (vertCheck + 12 > end) { pos += 4; continue; }

            float vx = Float.intBitsToFloat(readIntLE(data, vertCheck));
            float vy = Float.intBitsToFloat(readIntLE(data, vertCheck + 4));
            float vz = Float.intBitsToFloat(readIntLE(data, vertCheck + 8));

            if (Math.abs(vx) > 10000 || Math.abs(vy) > 10000 || Math.abs(vz) > 10000) {
                pos += 4;
                continue;
            }

            ConvexHdr hdr = new ConvexHdr();
            hdr.vertexOffset = relVertOffset;
            hdr.boneIndex = boneIdx;
            hdr.flags = flags;
            hdr.triCount = triCount;
            hdr.headerStartRel = pos - absoluteStart;
            headers.add(hdr);
            pos += 16;
        }

        if (!headers.isEmpty()) {
            LOGGER.debug("[PhyParser] Heuristically found {} convex hull headers for solid '{}'", headers.size(), solid.name);
            buildConvexHulls(data, absoluteStart, size, solid, headers);
        }
    }

    private static void buildConvexHulls(byte[] data, int absoluteStart, int size,
                                          PhySolid solid, List<ConvexHdr> headers) {
        int end = absoluteStart + size;

        for (int ci = 0; ci < headers.size(); ci++) {
            ConvexHdr hdr = headers.get(ci);

            PhyConvexHull hull = new PhyConvexHull();
            hull.vertexOffset = hdr.vertexOffset;
            hull.boneIndex = hdr.boneIndex;
            hull.flags = hdr.flags;
            hull.triangleCount = hdr.triCount;

            int triStartRel = hdr.headerStartRel + 16;
            int triEndRel = hdr.vertexOffset;

            if (ci + 1 < headers.size()) {
                ConvexHdr next = headers.get(ci + 1);
                int nextHdrStart = next.headerStartRel;
                if (nextHdrStart > hdr.headerStartRel && nextHdrStart < triEndRel)
                    triEndRel = nextHdrStart;
                int nextVertStart = next.vertexOffset;
                if (nextVertStart > hdr.headerStartRel && nextVertStart < triEndRel)
                    triEndRel = nextVertStart;
            }

            if (triEndRel > size) triEndRel = size;
            if (triStartRel < 0) triStartRel = 0;

            int maxTris = Math.max(0, (triEndRel - triStartRel) / 16);
            int trisToRead = Math.min(hdr.triCount, maxTris);

            for (int t = 0; t < trisToRead; t++) {
                int triOff = absoluteStart + triStartRel + t * 16;
                if (triOff + 16 > end) break;

                PhyTriangle tri = new PhyTriangle();
                tri.vertexIndex = data[triOff] & 0xFF;
                tri.v1 = readShortLE(data, triOff + 6);
                tri.v2 = readShortLE(data, triOff + 10);
                tri.v3 = readShortLE(data, triOff + 14);
                hull.triangles.add(tri);
            }

            int vertStartRel = hdr.vertexOffset;
            if (vertStartRel > 0 && vertStartRel < size) {
                int vertEndRel = size;
                if (ci + 1 < headers.size()) {
                    int nextVS = headers.get(ci + 1).vertexOffset;
                    if (nextVS > vertStartRel && nextVS < vertEndRel)
                        vertEndRel = nextVS;
                }
                int numVerts = Math.max(0, (vertEndRel - vertStartRel) / 16);
                for (int v = 0; v < numVerts; v++) {
                    int vo = absoluteStart + vertStartRel + v * 16;
                    if (vo + 12 > end) break;
                    PhyVertex vert = new PhyVertex();
                    vert.x = Float.intBitsToFloat(readIntLE(data, vo));
                    vert.y = Float.intBitsToFloat(readIntLE(data, vo + 4));
                    vert.z = Float.intBitsToFloat(readIntLE(data, vo + 8));
                    hull.vertices.add(vert);
                }
            }

            solid.convexHulls.add(hull);
        }
    }

    private static int readIntLE(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8) |
               ((data[off+2] & 0xFF) << 16) | ((data[off+3] & 0xFF) << 24);
    }

    private static short readShortLE(byte[] data, int off) {
        return (short)((data[off] & 0xFF) | ((data[off+1] & 0xFF) << 8));
    }

    private static String readFixedString(ByteBuffer buf) {
        byte[] bytes = new byte[4];
        buf.get(bytes);
        int nullTerm = 0;
        while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
        return new String(bytes, 0, nullTerm, StandardCharsets.UTF_8);
    }

    private static String readFixedString(ByteBuffer buf, int absPos) {
        int oldPos = buf.position();
        buf.position(absPos);
        byte[] bytes = new byte[4];
        buf.get(bytes);
        buf.position(oldPos);
        int nullTerm = 0;
        while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
        return new String(bytes, 0, nullTerm, StandardCharsets.UTF_8);
    }

    private static class ConvexHdr {
        int vertexOffset;  // relative to surface data start
        int boneIndex;
        int flags;
        int triCount;
        int headerStartRel;  // relative to surface data start
    }
}