package transferstation.transferstation_whimsicalideas.client.particle.collision;

import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.particle.Particle;

/**
 * 碰撞接口：可注入（JUnit 可测），与 MC Level 解耦。
 * 返回非 null 表示命中；alive=false 表示粒子应消失。
 */
public interface ParticleCollisionHandler {
    record Collision(Vector3f normal, float restitution, boolean alive) {}

    Collision collide(Particle p, float dt);
}
