package transferstation.transferstation_whimsicalideas.client.particle;

import net.minecraft.world.level.Level;
import org.joml.Math;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ParticleEmitter {
    private final PcfParticleSystemDef.SystemDefinition def;
    private final Level level;
    public final Vector3f origin = new Vector3f();
    public boolean active = true;
    private final List<Particle> particles = new ArrayList<>();
    private float emissionAccumulator = 0f;
    private final Consumer<Particle> onSpawn;

    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level) {
        this(def, level, null);
    }

    public ParticleEmitter(PcfParticleSystemDef.SystemDefinition def, Level level, Consumer<Particle> onSpawn) {
        this.def = def;
        this.level = level;
        this.onSpawn = onSpawn;
    }

    public void tick(float dt) {
        if (!active) return;

        // Emit new particles
        if (def.continuous) {
            emissionAccumulator += def.emissionRate * dt;
            while (emissionAccumulator >= 1f && particles.size() < def.maxParticles) {
                spawnParticle();
                emissionAccumulator -= 1f;
            }
        }

        // Update existing particles
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.tick(dt);
            applyOperators(p, dt);
            if (!p.alive) {
                it.remove();
            }
        }
    }

    private void spawnParticle() {
        Particle p = new Particle();
        p.lifetime = def.lifespan > 0 ? def.lifespan : 1f;
        p.position.set(origin);

        // Apply initializers
        for (var init : def.initializers) {
            applyInitializer(p, init);
        }

        // Apply renderer defaults
        if (def.renderer != null) {
            p.size = switch (def.renderer.type) {
                case SPRITE, DECAL -> 16f;
                case MODEL -> 32f;
                default -> 8f;
            };
        }

        particles.add(p);
        if (onSpawn != null) onSpawn.accept(p);
    }

    private void applyInitializer(Particle p, PcfParticleSystemDef.InitializerDef init) {
        var rand = level.random;
        switch (init.type) {
            case "position_sphere" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float theta = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                float phi = Math.acos(2 * rand.nextFloat() - 1);
                float r = radius * (float) java.lang.Math.cbrt(rand.nextFloat());
                p.position.x += r * org.joml.Math.sin(phi) * org.joml.Math.cos(theta);
                p.position.y += r * org.joml.Math.sin(phi) * org.joml.Math.sin(theta);
                p.position.z += r * org.joml.Math.cos(phi);
            }
            case "position_box" -> {
                float sx = getFloatParam(init.params, "m_flSizeX", 16f);
                float sy = getFloatParam(init.params, "m_flSizeY", 16f);
                float sz = getFloatParam(init.params, "m_flSizeZ", 16f);
                p.position.x += (rand.nextFloat() - 0.5f) * sx * 2;
                p.position.y += (rand.nextFloat() - 0.5f) * sy * 2;
                p.position.z += (rand.nextFloat() - 0.5f) * sz * 2;
            }
            case "velocity_random" -> {
                float minSpeed = getFloatParam(init.params, "m_flMinSpeed", 0f);
                float maxSpeed = getFloatParam(init.params, "m_flMaxSpeed", 100f);
                float speed = minSpeed + rand.nextFloat() * (maxSpeed - minSpeed);
                float theta = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                float phi = Math.acos(2 * rand.nextFloat() - 1);
                p.velocity.x = speed * org.joml.Math.sin(phi) * org.joml.Math.cos(theta);
                p.velocity.y = speed * org.joml.Math.sin(phi) * org.joml.Math.sin(theta);
                p.velocity.z = speed * org.joml.Math.cos(phi);
            }
            case "color_random" -> {
                float rMin = getFloatParam(init.params, "m_flColorMinR", 0.5f);
                float rMax = getFloatParam(init.params, "m_flColorMaxR", 1f);
                float gMin = getFloatParam(init.params, "m_flColorMinG", 0.5f);
                float gMax = getFloatParam(init.params, "m_flColorMaxG", 1f);
                float bMin = getFloatParam(init.params, "m_flColorMinB", 0.5f);
                float bMax = getFloatParam(init.params, "m_flColorMaxB", 1f);
                p.color.x = rMin + rand.nextFloat() * (rMax - rMin);
                p.color.y = gMin + rand.nextFloat() * (gMax - gMin);
                p.color.z = bMin + rand.nextFloat() * (bMax - bMin);
            }
            case "alpha_random" -> {
                float aMin = getFloatParam(init.params, "m_flAlphaMin", 0.5f);
                float aMax = getFloatParam(init.params, "m_flAlphaMax", 1f);
                p.color.w = aMin + rand.nextFloat() * (aMax - aMin);
            }
            case "lifetime_random" -> {
                float minLife = getFloatParam(init.params, "m_flLifetimeMin", 0.5f);
                float maxLife = getFloatParam(init.params, "m_flLifetimeMax", 3f);
                p.lifetime = minLife + rand.nextFloat() * (maxLife - minLife);
            }
            case "size_random" -> {
                float minSize = getFloatParam(init.params, "m_flSizeMin", 4f);
                float maxSize = getFloatParam(init.params, "m_flSizeMax", 16f);
                p.size = minSize + rand.nextFloat() * (maxSize - minSize);
            }
            case "rotation_random" -> {
                float minRot = getFloatParam(init.params, "m_flRotMin", 0f);
                float maxRot = getFloatParam(init.params, "m_flRotMax", (float) (org.joml.Math.PI * 2));
                p.rotation = minRot + rand.nextFloat() * (maxRot - minRot);
                float minAV = getFloatParam(init.params, "m_flAngVelMin", -3f);
                float maxAV = getFloatParam(init.params, "m_flAngVelMax", 3f);
                p.angularVelocity = minAV + rand.nextFloat() * (maxAV - minAV);
            }
            case "position_circle" -> {
                float radius = getFloatParam(init.params, "m_flRadius", 16f);
                float angle = rand.nextFloat() * (float) (org.joml.Math.PI * 2);
                p.position.x += radius * org.joml.Math.cos(angle);
                p.position.z += radius * org.joml.Math.sin(angle);
                p.position.y += getFloatParam(init.params, "m_flHeight", 0f) * (rand.nextFloat() - 0.5f);
            }
        }
    }

    private void applyOperators(Particle p, float dt) {
        for (var op : def.operators) {
            switch (op.type) {
                case "gravity" -> {
                    float g = getFloatParam(op.params, "m_flGravity", 400f);
                    p.velocity.y -= g * dt;
                }
                case "friction", "damping" -> {
                    float drag = getFloatParam(op.params, "m_flDrag", 0.1f);
                    p.velocity.mul(1f - drag * dt);
                }
                case "noise" -> {
                    float strength = getFloatParam(op.params, "m_flStrength", 10f);
                    float freq = getFloatParam(op.params, "m_flFrequency", 1f);
                    float phase = p.age * freq;
                    float noiseX = Math.sin(phase + p.position.x * 0.1f);
                    float noiseY = Math.cos(phase + p.position.y * 0.1f);
                    float noiseZ = Math.sin(phase + p.position.z * 0.1f);
                    p.velocity.x += noiseX * strength * dt;
                    p.velocity.y += noiseY * strength * dt;
                    p.velocity.z += noiseZ * strength * dt;
                }
                case "color_fade" -> {
                    float r = getFloatParam(op.params, "m_flFadeR", 0f);
                    float g = getFloatParam(op.params, "m_flFadeG", 0f);
                    float b = getFloatParam(op.params, "m_flFadeB", 0f);
                    float progress = p.getProgress();
                    p.color.x = Math.lerp(p.color.x, r, progress * 0.1f);
                    p.color.y = Math.lerp(p.color.y, g, progress * 0.1f);
                    p.color.z = Math.lerp(p.color.z, b, progress * 0.1f);
                }
                case "alpha_fade" -> {
                    float startAlpha = getFloatParam(op.params, "m_flStartAlpha", 1f);
                    float endAlpha = getFloatParam(op.params, "m_flEndAlpha", 0f);
                    float progress = p.getProgress();
                    p.color.w = startAlpha + (endAlpha - startAlpha) * progress;
                }
                case "size_scale" -> {
                    float startSize = getFloatParam(op.params, "m_flStartSize", 1f);
                    float endSize = getFloatParam(op.params, "m_flEndSize", 0.5f);
                    float progress = p.getProgress();
                    p.size = p.size * (startSize + (endSize - startSize) * progress);
                }
                case "oscillator" -> {
                    float amp = getFloatParam(op.params, "m_flAmplitude", 5f);
                    float freq = getFloatParam(op.params, "m_flFrequency", 2f);
                    float phase = p.age * freq * (float) (org.joml.Math.PI * 2);
                    p.position.x += org.joml.Math.sin(phase) * amp * dt;
                    p.position.y += org.joml.Math.cos(phase) * amp * dt;
                }
                case "vortex" -> {
                    float strength = getFloatParam(op.params, "m_flStrength", 50f);
                    float radius = new Vector3f(p.position).sub(origin).length();
                    if (radius > 0.01f) {
                        Vector3f tangential = new Vector3f(
                            -(p.position.z - origin.z),
                            0,
                            (p.position.x - origin.x)
                        ).normalize();
                        p.velocity.add(tangential.mul(strength * dt));
                        p.velocity.add(new Vector3f(origin).sub(p.position).normalize().mul(strength * 0.3f * dt));
                    }
                }
                case "wind" -> {
                    float strength = getFloatParam(op.params, "m_flWindStrength", 50f);
                    float dirX = getFloatParam(op.params, "m_vWindDirX", 1f);
                    float dirY = getFloatParam(op.params, "m_vWindDirY", 0f);
                    float dirZ = getFloatParam(op.params, "m_vWindDirZ", 0f);
                    p.velocity.x += dirX * strength * dt;
                    p.velocity.y += dirY * strength * dt;
                    p.velocity.z += dirZ * strength * dt;
                }
            }
        }
    }

    private float getFloatParam(Map<String, Object> params, String key, float def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.floatValue();
        return def;
    }

    public List<Particle> getParticles() {
        return particles;
    }

    public int getParticleCount() { return particles.size(); }

    public PcfParticleSystemDef.SystemDefinition getDefinition() { return def; }

    public void burst(int count) {
        for (int i = 0; i < count && particles.size() < def.maxParticles; i++) {
            spawnParticle();
        }
    }
}
