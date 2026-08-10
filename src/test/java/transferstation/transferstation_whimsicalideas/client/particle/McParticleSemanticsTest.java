package transferstation.transferstation_whimsicalideas.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McParticleSemanticsTest {
    @Test
    void additiveMaterialEnablesAdditiveBlend() {
        var renderer = new PcfParticleSystemDef.RendererDef();
        renderer.materialPath = "particle/fire.vmt";
        renderer.additive = true;
        assertTrue(McParticleSemantics.useAdditiveBlend(renderer));
    }

    @Test
    void normalMaterialUsesDefaultBlend() {
        var renderer = new PcfParticleSystemDef.RendererDef();
        renderer.materialPath = "particle/water.vmt";
        renderer.additive = false;
        assertFalse(McParticleSemantics.useAdditiveBlend(renderer));
    }

    @Test
    void fullBrightOnlyWhenMarked() {
        var renderer = new PcfParticleSystemDef.RendererDef();
        renderer.materialPath = "particle/fire.vmt";
        assertTrue(McParticleSemantics.isFullBright(renderer));
        renderer.materialPath = "particle/dirt.vmt";
        assertFalse(McParticleSemantics.isFullBright(renderer));
    }

    @Test
    void mcGravityConstantMatchesVanilla() {
        // Source 重力 op 默认 400 单位/s² → MC 渲染语义使用 -0.04 blocks/tick²
        assertEquals(400f, McParticleSemantics.SOURCE_GRAVITY_UNITS, 0.001f);
        assertEquals(-0.04f, McParticleSemantics.MC_GRAVITY_BLOCKS_PER_TICK2, 0.001f);
    }
}
