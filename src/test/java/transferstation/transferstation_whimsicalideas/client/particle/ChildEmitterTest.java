package transferstation.transferstation_whimsicalideas.client.particle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildEmitterTest {

    @Test
    void triggersChildAfterDelay() {
        var def = new PcfParticleSystemDef.SystemDefinition();
        def.name = "parent";
        var child = new PcfParticleSystemDef.ChildDef();
        child.childName = "sparks";
        child.delay = 0.5f;
        child.delayRate = 0.25f;
        def.children.add(child);
        def.continuous = true;

        List<String> spawned = new ArrayList<>();
        var emitter = new ParticleEmitter(def, new Random(1));
        emitter.setOnChildSpawn(spawned::add);

        emitter.tick(0.2f);  // 累积 0.2，未到 0.5
        assertEquals(0, spawned.size());
        emitter.tick(0.2f);  // 累积 0.4
        emitter.tick(0.2f);  // 累积 0.6 ≥ 0.5 → 触发
        assertEquals(List.of("sparks"), spawned);
        emitter.tick(0.2f);  // 下一次延迟 = 0.5 + 0.25 = 0.75
        emitter.tick(0.2f);  // 累积 0.4 < 0.75
        assertEquals(1, spawned.size());
    }

    @Test
    void noChildrenDoesNothing() {
        var def = new PcfParticleSystemDef.SystemDefinition();
        def.name = "parent";
        def.continuous = true;

        List<String> spawned = new ArrayList<>();
        var emitter = new ParticleEmitter(def, new Random(1));
        emitter.setOnChildSpawn(spawned::add);

        emitter.tick(1f);
        emitter.tick(1f);
        assertTrue(spawned.isEmpty());
    }
}
