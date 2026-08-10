package transferstation.transferstation_whimsicalideas.client.particle;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import transferstation.transferstation_whimsicalideas.client.particle.collision.ParticleCollisionHandler;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleCollisionTest {

    // 假碰撞器：只对 y < 0 触发，法线向上
    static class FloorHandler implements ParticleCollisionHandler {
        @Override
        public Collision collide(Particle p, float dt) {
            if (p.position.y < 0) {
                return new Collision(new Vector3f(0, 1, 0), 0.3f, true); // 法线、回弹系数、存活
            }
            return null;
        }
    }

    // 假碰撞器：只对 y < -5 触发，且粒子应消失
    static class KillHandler implements ParticleCollisionHandler {
        @Override
        public Collision collide(Particle p, float dt) {
            if (p.position.y < -5) {
                return new Collision(new Vector3f(0, 1, 0), 0f, false);
            }
            return null;
        }
    }

    @Test
    void bouncesVelocityOffSurface() {
        var def = new PcfParticleSystemDef.SystemDefinition();
        def.continuous = true;
        var emitter = new ParticleEmitter(def, new Random(1), null, new FloorHandler(), null);
        emitter.origin.set(0, 0, 0);
        emitter.burst(1);
        Particle p = emitter.getParticles().get(0);
        p.velocity.set(0, -50, 0);
        p.position.set(0, -1, 0);
        emitter.tick(0.05f);
        assertEquals(15f, p.velocity.y, 0.001f);   // -50 * -0.3 = +15
        assertTrue(p.alive);
        assertTrue(p.hitThisTick);
    }

    @Test
    void collisionKillFlagRemovesParticle() {
        var def = new PcfParticleSystemDef.SystemDefinition();
        def.continuous = true;
        var emitter = new ParticleEmitter(def, new Random(1), null, new KillHandler(), null);
        emitter.origin.set(0, 0, 0);
        emitter.burst(1);
        Particle p = emitter.getParticles().get(0);
        p.velocity.set(0, -200, 0);
        p.position.set(0, -10, 0);
        emitter.tick(0.05f);
        assertEquals(0, emitter.getParticleCount());
    }

    @Test
    void noCollisionHandlerMeansNoHitFlag() {
        var def = new PcfParticleSystemDef.SystemDefinition();
        def.continuous = true;
        var emitter = new ParticleEmitter(def, new Random(1));
        emitter.origin.set(0, 0, 0);
        emitter.burst(1);
        Particle p = emitter.getParticles().get(0);
        p.position.set(0, -10, 0);
        emitter.tick(0.05f);
        assertTrue(p.alive);
        assertTrue(!p.hitThisTick);
    }
}
