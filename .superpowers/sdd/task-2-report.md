# Task 2 Report: Particle & ParticleEmitter Runtime

## Implementation Summary

Created two Java source files implementing the runtime core of the particle system:

### `Particle.java`
- Data class representing a single particle with position, velocity, color, size, rotation, age, lifetime, and animation frame state
- `tick(float dt)` — updates position via velocity, increments age, tracks previous position for trail rendering
- `getProgress()` — normalized lifetime [0..1]
- Alpha getter/setter delegates to `color.w`

### `ParticleEmitter.java`
- Manages a pool of `Particle` instances driven by a `PcfParticleSystemDef.SystemDefinition`
- Continuous emission mode with configurable rate and max particles
- `tick(float dt)` — emits new particles when continuous, updates all alive particles, applies per-frame operators, removes dead particles
- `burst(int count)` — one-shot emission up to `maxParticles`
- 12 initializer types: `position_sphere`, `position_box`, `position_circle`, `velocity_random`, `color_random`, `alpha_random`, `lifetime_random`, `size_random`, `rotation_random`
- 10 operator types: `gravity`, `friction`/`damping`, `noise`, `color_fade`, `alpha_fade`, `size_scale`, `oscillator`, `vortex`, `wind`
- `onSpawn` callback for external hooks (e.g., attaching trail emitters)
- `renderer.type` switch sets default particle size per renderer kind

## Files Modified
- `src/main/java/.../particle/Particle.java` — created (47 lines)
- `src/main/java/.../particle/ParticleEmitter.java` — created (299 lines)

## Self-Review Findings
- **Fixed:** 5 instances of `org.joml.Math.PI * 2` (double) being assigned to float variables without explicit cast — added `(float)` wrapper.
- **Minor assumption:** `java.lang.Math.cbrt()` used in `position_sphere` initializer because `org.joml.Math` does not expose `cbrt`. The spec used bare `Math.cbrt`, but since `org.joml.Math` is imported, this would fail to compile. Using fully-qualified `java.lang.Math.cbrt` resolves this cleanly.
- **Note:** `Random` noise operator uses `Math.random()` (java.lang) rather than level random — acceptable for visual noise; not deterministic but typical for particle effects.

## Concerns
- No unit tests exist yet for Particle or ParticleEmitter; they depend on Minecraft's `Level` and Forge environment.
- The `forces` field on `SystemDefinition` is not consumed by the emitter — may need a future task to wire it up.

## Verification
- **Build:** `gradlew compileJava` — **BUILD SUCCESSFUL** (4 actionable tasks, 2 executed, 2 up-to-date)
