package transferstation.transferstation_whimsicalideas.client.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a static triangle-soup mesh from the visible block surfaces around a
 * center position. The mesh is fed to the native physics engine so sphere-shaped
 * rigid bodies (ragdoll bones, cloth) collide against real terrain instead of
 * clipping through it.
 */
public final class EnvironmentMeshBuilder {

    private static final float BLOCK = 1.0f;

    /** Triangulates block faces around {@code center} within {@code radius} blocks. */
    public static MeshData build(Level level, BlockPos center, int radius) {
        List<Float> verts = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();
        int baseX = center.getX();
        int baseY = center.getY();
        int baseZ = center.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    BlockPos pos = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    // Skip blocks that don't block motion entirely (e.g. water, tall grass)
                    if (!state.isSolid()) continue;

                    boolean solid = isSolidBlock(level, pos);
                    if (!solid) continue;

                    addFace(verts, idxs, pos, Face.PX, isOpen(level, pos, 1, 0, 0));
                    addFace(verts, idxs, pos, Face.NX, isOpen(level, pos, -1, 0, 0));
                    addFace(verts, idxs, pos, Face.PY, isOpen(level, pos, 0, 1, 0));
                    addFace(verts, idxs, pos, Face.NY, isOpen(level, pos, 0, -1, 0));
                    addFace(verts, idxs, pos, Face.PZ, isOpen(level, pos, 0, 0, 1));
                    addFace(verts, idxs, pos, Face.NZ, isOpen(level, pos, 0, 0, -1));
                }
            }
        }

        return new MeshData(toFloatArray(verts), toIntArray(idxs));
    }

    private static boolean isSolidBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.isCollisionShapeFullBlock(level, pos);
    }

    private static boolean isOpen(Level level, BlockPos pos, int dx, int dy, int dz) {
        BlockPos neighbor = pos.offset(dx, dy, dz);
        BlockState state = level.getBlockState(neighbor);
        return state.isAir() || !state.isSolid();
    }

    private static void addFace(List<Float> verts, List<Integer> idxs, BlockPos pos,
                                Face face, boolean open) {
        if (!open) return;
        float x = pos.getX();
        float y = pos.getY();
        float z = pos.getZ();
        int base = verts.size() / 3;

        float[] corners = face.corners();
        for (int i = 0; i < 4; i++) {
            verts.add(x + corners[i * 3 + 0]);
            verts.add(y + corners[i * 3 + 1]);
            verts.add(z + corners[i * 3 + 2]);
        }
        idxs.add(base);
        idxs.add(base + 1);
        idxs.add(base + 2);
        idxs.add(base);
        idxs.add(base + 2);
        idxs.add(base + 3);
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private enum Face {
        PX(new float[]{1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1}),
        NX(new float[]{0, 0, 1, 0, 1, 1, 0, 1, 0, 0, 0, 0}),
        PY(new float[]{0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1}),
        NY(new float[]{0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0}),
        PZ(new float[]{0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1}),
        NZ(new float[]{1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0});

        private final float[] c;
        Face(float[] c) { this.c = c; }
        float[] corners() { return c; }
    }

    /** Interleaved xyz vertices plus triangle indices, ready for the native bridge. */
    public record MeshData(float[] vertices, int[] indices) {
        public int vertexCount() { return vertices.length / 3; }
        public int triangleCount() { return indices.length / 3; }
    }
}
