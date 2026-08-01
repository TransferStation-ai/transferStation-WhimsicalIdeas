package transferstation.transferstation_whimsicalideas.client.model;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MdlFlexAnimation {

    public static class FlexVertex {
        public int vertexIndex;
        public float[] delta = new float[3];
        public float[] ndelta = new float[3];
        public float wrinkle;
        public byte speed;
        public byte side;
    }

    public static class FlexAnimation {
        public int flexDesc;
        public float[] targets = new float[4];
        public int vertAnimType;
        public int flexPair;
        public List<FlexVertex> vertices = new ArrayList<>();
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static void parse(ByteBuffer buf, MdlDataTypes.ParsedModel result, int bufferLimit) {
        if (result.meshes == null || result.meshes.isEmpty()) return;
        if (result.models == null || result.models.isEmpty()) return;

        float fixedPointScale = result.header.vertAnimFixedPointScale;
        if (fixedPointScale <= 0) fixedPointScale = 1.0f / 4096.0f;

        for (int mi = 0; mi < result.meshes.size(); mi++) {
            MdlDataTypes.Mesh mesh = result.meshes.get(mi);
            if (mesh.numflexes <= 0 || mesh.flexindex <= 0) {
                result.meshFlexAnimations.add(new ArrayList<>());
                continue;
            }

            int meshEntryOff = findMeshEntryOffset(result, mi);
            int flexDataBase = meshEntryOff + mesh.flexindex;

            List<FlexAnimation> flexList = new ArrayList<>();
            int numFlexes = Math.min(mesh.numflexes, 64);

            for (int f = 0; f < numFlexes; f++) {
                if (flexDataBase + 48 > bufferLimit) break;

                FlexAnimation flex = new FlexAnimation();
                try {
                    flex.flexDesc = buf.getInt(flexDataBase);
                    flex.targets[0] = buf.getFloat(flexDataBase + 4);
                    flex.targets[1] = buf.getFloat(flexDataBase + 8);
                    flex.targets[2] = buf.getFloat(flexDataBase + 12);
                    flex.targets[3] = buf.getFloat(flexDataBase + 16);
                    int numVerts = buf.getInt(flexDataBase + 20);
                    int vertIndex = buf.getInt(flexDataBase + 24);
                    flex.flexPair = buf.getInt(flexDataBase + 28);
                    flex.vertAnimType = buf.get(flexDataBase + 32) & 0xFF;

                    int numVertsToRead = Math.min(numVerts, 65536);
                    int vertSize = (flex.vertAnimType == 1) ? 18 : 16;
                    int vertDataAddr = flexDataBase + vertIndex;

                    if (vertIndex > 0 && vertDataAddr + numVertsToRead * vertSize <= bufferLimit) {
                        for (int v = 0; v < numVertsToRead; v++) {
                            int vOff = vertDataAddr + v * vertSize;
                            FlexVertex fv = new FlexVertex();
                            fv.vertexIndex = buf.getShort(vOff) & 0xFFFF;
                            fv.speed = buf.get(vOff + 2);
                            fv.side = buf.get(vOff + 3);
                            fv.delta[0] = buf.getShort(vOff + 4) * fixedPointScale;
                            fv.delta[1] = buf.getShort(vOff + 6) * fixedPointScale;
                            fv.delta[2] = buf.getShort(vOff + 8) * fixedPointScale;
                            fv.ndelta[0] = buf.getShort(vOff + 10) * fixedPointScale;
                            fv.ndelta[1] = buf.getShort(vOff + 12) * fixedPointScale;
                            fv.ndelta[2] = buf.getShort(vOff + 14) * fixedPointScale;
                            if (flex.vertAnimType == 1 && vOff + 18 <= bufferLimit) {
                                fv.wrinkle = buf.getShort(vOff + 16) * fixedPointScale;
                            }
                            flex.vertices.add(fv);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[MdlParser] Flex parse error at mesh {} flex {}: {}", mi, f, e.getMessage());
                }
                flexList.add(flex);
                flexDataBase += 48;
            }
            result.meshFlexAnimations.add(flexList);
        }
    }

    private static int findMeshEntryOffset(MdlDataTypes.ParsedModel result, int meshIndex) {
        int meshCount = 0;
        for (MdlDataTypes.Model m : result.models) {
            if (meshCount + m.nummeshes > meshIndex) {
                int localMesh = meshIndex - meshCount;
                return m.fileOffset + m.meshindex + localMesh * result.meshSize;
            }
            meshCount += m.nummeshes;
        }
        return 0;
    }
}
