package transferstation.transferstation_whimsicalideas.npc.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Drives simple autonomous NPC behaviors (follow a player, chop wood).
 * Goals are added/removed from the owning Mob's goal selector on demand so the
 * behavior can be toggled at runtime without reconstructing the entity.
 */
public class AINpcAgent {

    private final Mob npc;
    private String mode = "";

    private FollowOwnerGoal followGoal;
    private ChopWoodGoal chopGoal;

    public AINpcAgent(Mob npc) {
        this.npc = npc;
    }

    public void orderChopWood() {
        clearGoals();
        this.mode = "chop_wood";
        this.chopGoal = new ChopWoodGoal(npc);
        npc.goalSelector.addGoal(2, chopGoal);
    }

    public void orderFollowPlayer(Mob toFollow) {
        clearGoals();
        this.mode = "follow";
        this.followGoal = new FollowOwnerGoal(npc, toFollow, 1.0, 4.0f, 2.0f);
        npc.goalSelector.addGoal(2, followGoal);
    }

    public void clearOrders() {
        clearGoals();
        this.mode = "";
    }

    private void clearGoals() {
        if (followGoal != null) {
            npc.goalSelector.removeGoal(followGoal);
            followGoal = null;
        }
        if (chopGoal != null) {
            npc.goalSelector.removeGoal(chopGoal);
            chopGoal = null;
        }
    }

    public void tick() {
        // Goals are driven by the Minecraft AI tick loop; nothing to poll here.
        // Kept for API compatibility with callers that invoke aiAgent.tick().
    }

    /**
     * Walks to the nearest wood log within range and mines it (drops the item).
     * Re-targets when the current log is gone, so the NPC keeps chopping until
     * no logs remain nearby.
     */
    private static class ChopWoodGoal extends Goal {
        private final Mob npc;
        private BlockPos targetLog;
        private int cooldown = 0;

        ChopWoodGoal(Mob npc) {
            this.npc = npc;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (cooldown > 0) {
                cooldown--;
                return false;
            }
            if (targetLog == null || !isWood(npc.level(), targetLog)) {
                targetLog = findNearestLog();
            }
            return targetLog != null;
        }

        @Override
        public boolean canContinueToUse() {
            return targetLog != null && isWood(npc.level(), targetLog)
                    && npc.distanceToSqr(targetLog.getX() + 0.5, targetLog.getY() + 0.5, targetLog.getZ() + 0.5) > 4.0;
        }

        @Override
        public void tick() {
            if (targetLog == null) return;
            double cx = targetLog.getX() + 0.5, cy = targetLog.getY() + 0.5, cz = targetLog.getZ() + 0.5;
            npc.getNavigation().moveTo(cx, targetLog.getY() + 0.5, cz, 1.0);

            double dist = npc.distanceToSqr(cx, cy, cz);
            if (dist <= 4.0 && npc.level() instanceof ServerLevel serverLevel) {
                BlockState state = serverLevel.getBlockState(targetLog);
                Block.dropResources(state, serverLevel, targetLog, null, npc, npc.getMainHandItem());
                serverLevel.removeBlock(targetLog, false);
                targetLog = null;
                cooldown = 20; // brief pause between chops
            }
        }

        private BlockPos findNearestLog() {
            Level level = npc.level();
            BlockPos origin = npc.blockPosition();
            int radius = 8;
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = origin.offset(dx, dy, dz);
                        if (isWood(level, pos)) {
                            double d = npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                            if (d < bestDist) {
                                bestDist = d;
                                best = pos;
                            }
                        }
                    }
                }
            }
            return best;
        }

        private static boolean isWood(Level level, BlockPos pos) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return false;
            // Detect any wood/log block (oak..mangrove, crimson/warped stems, stripped variants).
            return state.is(BlockTags.LOGS);
        }
    }

    /**
     * Follows a target entity, keeping within [startDist, stopDist] range.
     * Used by orderFollowPlayer(); the target is supplied at construction time.
     */
    private static class FollowOwnerGoal extends Goal {
        private final Mob npc;
        private final Entity target;
        private final double speed;
        private final float stopDist;
        private final float startDist;
        private int cooldown = 0;

        FollowOwnerGoal(Mob npc, Entity target, double speed, float stopDist, float startDist) {
            this.npc = npc;
            this.target = target;
            this.speed = speed;
            this.stopDist = stopDist;
            this.startDist = startDist;
            this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return target != null && target.isAlive();
        }

        @Override
        public boolean canContinueToUse() {
            if (target == null || !target.isAlive()) return false;
            double d = npc.distanceToSqr(target);
            return d > (double) stopDist * stopDist;
        }

        @Override
        public void start() {
            cooldown = 0;
        }

        @Override
        public void tick() {
            if (target == null) return;
            if (cooldown > 0) {
                cooldown--;
                return;
            }
            double d = npc.distanceToSqr(target);
            if (d > (double) startDist * startDist) {
                npc.getNavigation().moveTo(target, speed);
                cooldown = 10;
            }
        }
    }
}
