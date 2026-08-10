package transferstation.transferstation_whimsicalideas.client.particle.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认 MC 方块碰撞实现：检测粒子所在方块是否非空气且为实心，
 * 从粒子中心向相邻 6 方向找最近空气方块，以该方向为法线回弹。
 */
public class BlockCollisionHandler implements ParticleCollisionHandler {
    private final Level level;

    public BlockCollisionHandler(Level level) {
        this.level = level;
    }

    @Override
    public Collision collide(Particle p, float dt) {
        var pos = new BlockPos(
            (int) Math.floor(p.position.x),
            (int) Math.floor(p.position.y),
            (int) Math.floor(p.position.z));
        if (level.getBlockState(pos).isAir()
                || !level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
            return null;
        }
        // 最近面法线：从粒子中心找相邻空气方块方向
        List<Vec3> candidates = new ArrayList<>(6);
        candidates.add(new Vec3(1, 0, 0));
        candidates.add(new Vec3(-1, 0, 0));
        candidates.add(new Vec3(0, 1, 0));
        candidates.add(new Vec3(0, -1, 0));
        candidates.add(new Vec3(0, 0, 1));
        candidates.add(new Vec3(0, 0, -1));

        double bestDist = Double.MAX_VALUE;
        Vec3 bestNormal = new Vec3(0, 1, 0);
        for (Vec3 dir : candidates) {
            var neighbor = pos.offset((int) dir.x, (int) dir.y, (int) dir.z);
            if (level.getBlockState(neighbor).isAir()
                    || !level.getBlockState(neighbor).isCollisionShapeFullBlock(level, neighbor)) {
                // 空气方块中心与粒子中心的距离
                double cx = neighbor.getX() + 0.5 - p.position.x;
                double cy = neighbor.getY() + 0.5 - p.position.y;
                double cz = neighbor.getZ() + 0.5 - p.position.z;
                double dist = cx * cx + cy * cy + cz * cz;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestNormal = dir;
                }
            }
        }
        // 粒子在方块内部且没有相邻空气 → 直接向上弹（保守回退）
        return new Collision(
            new org.joml.Vector3f((float) bestNormal.x, (float) bestNormal.y, (float) bestNormal.z),
            0.3f, true);
    }
}
