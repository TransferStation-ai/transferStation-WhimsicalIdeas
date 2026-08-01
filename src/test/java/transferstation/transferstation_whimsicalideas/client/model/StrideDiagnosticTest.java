package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 诊断 VTX 顶点步长（stride）检测是否正确。
 * 在 GMOD（SDK2013）模型中，Vertex_t 是固定的 8 字节布局：
 *   boneID[2] + boneWeight[3] + numBones + origMeshVertID[ushort]
 * 在新版工具链（Crowbar等）中，布局是可变长度（maxBonesPerVert*2+3 字节）：
 *   boneWeight[maxBonesPerVert] + numBones + origMeshVertID + boneID[maxBonesPerVert]
 *
 * 此测试直接读取 VTX 文件的第一个 strip group 的顶点数据，
 * 然后用 stride=8 offset=6 和 stride=9 offset=4 分别读取 origMeshVertID，
 * 看哪种布局产生平滑递增的序列（表明是正确解析）。
 */
public class StrideDiagnosticTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Path TEST_MODEL_DIR = Paths.get(
        "run", "config", "transferstation_whimsicalideas", "models",
        "0v0NekoWork_Chiffon_GothicTacMaid",
        "models", "pm"
    );

    @Test
    public void verifyStrideDetection() throws IOException {
        Path vtxFile = TEST_MODEL_DIR.resolve("ChiffonGothicTacMaid.dx90.vtx");
        assertTrue(Files.exists(vtxFile));

        byte[] data = Files.readAllBytes(vtxFile);
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Parse VTX header (sequential, not random-access)
        int headerVersion = buf.getInt();
        int vertCacheSize = buf.getInt();
        int maxBonesPerStrip = buf.getShort() & 0xFFFF;
        int maxBonesPerTri = buf.getShort() & 0xFFFF;
        int maxBonesPerVert = buf.getInt();
        int checksum = buf.getInt();
        int numLODs = buf.getInt();
        int materialReplacementListOffset = buf.getInt();
        int numBodyParts = buf.getInt();
        int bodyPartOffset = buf.getInt();

        LOGGER.info("VTX: version={} maxBonesPerVert={} numBodyParts={} bodyPartOffset=0x{}",
            headerVersion, maxBonesPerVert, numBodyParts, Integer.toHexString(bodyPartOffset));

        // Navigate to first model's LOD0's first mesh's first strip group
        int bpAddr = bodyPartOffset;
        int numModels = buf.getInt(bpAddr);
        int modelOff = buf.getInt(bpAddr + 4);
        int modelAddr = bpAddr + modelOff;

        int numLOD = buf.getInt(modelAddr);
        int lodOff = buf.getInt(modelAddr + 4);
        int lodAddr = modelAddr + lodOff;

        int numMeshes = buf.getInt(lodAddr);
        int meshOff = buf.getInt(lodAddr + 4);
        int meshAddr = lodAddr + meshOff;

        int meshHeaderSize = (headerVersion >= 7) ? 9 : 8;
        int numStripGroups = buf.getInt(meshAddr);
        int sgOff = buf.getInt(meshAddr + 4);
        int sgAddr = meshAddr + sgOff;

        int numVerts = buf.getInt(sgAddr);
        int vertOff = buf.getInt(sgAddr + 4);
        int vertDataAddr = sgAddr + vertOff;

        LOGGER.info("First mesh: numStripGroups={} numVerts={} vertDataAddr=0x{}",
            numStripGroups, numVerts, Integer.toHexString(vertDataAddr));

        // Try stride=8 offset=6 (GMOD layout)
        LOGGER.info("--- Stride=8 offset=6 (GMOD/SDK2013 fixed layout) ---");
        int[] ids8 = new int[numVerts];
        for (int i = 0; i < numVerts && i < 20; i++) {
            int off = vertDataAddr + i * 8 + 6;
            int id = buf.getShort(off) & 0xFFFF;
            ids8[i] = id;
            LOGGER.info("  Vertex[{}]: offset=0x{} origMeshVertID={}", i, Integer.toHexString(off), id);
        }
        boolean seq8 = isMostlySequential(ids8, numVerts);
        LOGGER.info("  Stride=8: {} sequential", seq8 ? "LOOKS GOOD" : "GARBLED");

        // Try stride=9 offset=4 (variable layout)
        LOGGER.info("--- Stride=9 offset=4 (variable layout) ---");
        int[] ids9 = new int[numVerts];
        for (int i = 0; i < numVerts && i < 20; i++) {
            int off = vertDataAddr + i * 9 + 4;
            int id = buf.getShort(off) & 0xFFFF;
            ids9[i] = id;
            LOGGER.info("  Vertex[{}]: offset=0x{} origMeshVertID={}", i, Integer.toHexString(off), id);
        }
        boolean seq9 = isMostlySequential(ids9, numVerts);
        LOGGER.info("  Stride=9: {} sequential", seq9 ? "LOOKS GOOD" : "GARBLED");

        // Check ALL vertices for each hypothesis and look at statistics
        LOGGER.info("=== Full Vertex Scan ===");
        int monotonic8 = 0, monotonic9 = 0;
        int withinRange8 = 0, withinRange9 = 0;
        for (int i = 1; i < numVerts; i++) {
            int off8 = vertDataAddr + i * 8 + 6;
            ids8[i] = buf.getShort(off8) & 0xFFFF;
            if (ids8[i] > ids8[i-1]) monotonic8++;

            int off9 = vertDataAddr + i * 9 + 4;
            ids9[i] = buf.getShort(off9) & 0xFFFF;
            if (ids9[i] > ids9[i-1]) monotonic9++;
        }
        LOGGER.info("Stride=8: monotonic={}/{} first10={} last10={}",
            monotonic8, numVerts-1,
            java.util.Arrays.toString(java.util.Arrays.copyOf(ids8, Math.min(10, numVerts))),
            java.util.Arrays.toString(java.util.Arrays.copyOfRange(ids8, Math.max(0, numVerts-10), numVerts)));
        LOGGER.info("Stride=9: monotonic={}/{} first10={} last10={}",
            monotonic9, numVerts-1,
            java.util.Arrays.toString(java.util.Arrays.copyOf(ids9, Math.min(10, numVerts))),
            java.util.Arrays.toString(java.util.Arrays.copyOfRange(ids9, Math.max(0, numVerts-10), numVerts)));

        // Check if the chosen stride's IDs are valid mesh-relative indices
        int maxId8 = 0;
        for (int i = 0; i < numVerts; i++) {
            if (ids8[i] > maxId8) maxId8 = ids8[i];
        }
        int maxId9 = 0;
        for (int i = 0; i < numVerts; i++) {
            if (ids9[i] > maxId9) maxId9 = ids9[i];
        }
        LOGGER.info("Stride=8: max origMeshVertID={} (numVerts={}) {}",
            maxId8, numVerts, maxId8 < numVerts ? "VALID (within range)" : "EXCEEDS range");
        LOGGER.info("Stride=9: max origMeshVertID={} (numVerts={}) {}",
            maxId9, numVerts, maxId9 < numVerts ? "VALID (within range)" : "EXCEEDS range");

        // Conclusion
        LOGGER.info("=== CONCLUSION ===");
        if (seq8 && !seq9) {
            LOGGER.info("STRIDE=8 IS CORRECT (GMOD/SDK2013 layout)");
        } else if (seq9 && !seq8) {
            LOGGER.info("STRIDE=9 IS CORRECT (newer toolchain layout)");
        } else if (seq8 && seq9) {
            LOGGER.info("BOTH possible - need additional verification");
        } else {
            LOGGER.info("NEITHER - stride detection needs review");
        }
    }

    /** Check if first 20 IDs are mostly sequential (0,1,2,... pattern) */
    private boolean isMostlySequential(int[] ids, int limit) {
        int seqCount = 0;
        for (int i = 1; i < Math.min(limit, 20); i++) {
            if (ids[i] > ids[i-1] && ids[i] <= ids[i-1] + 5) seqCount++;
        }
        return seqCount >= Math.min(limit, 20) * 0.7;
    }
}
