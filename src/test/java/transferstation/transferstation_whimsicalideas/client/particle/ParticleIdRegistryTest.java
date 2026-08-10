package transferstation.transferstation_whimsicalideas.client.particle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParticleIdRegistryTest {
    @Test
    void mapsIdToSystemNameFromTxt() throws Exception {
        String txt = "particles_manifest.txt\n{\n\tfile\t\"particles/smoke.txt\"\n}\n";
        String smoke = "particles/smoke.txt\n{\n\tParticles\n\t{\n\t\t\"id\" \"1\"\n\t}\n}\n";
        ParticleIdRegistry reg = new ParticleIdRegistry(
            key -> switch (key) {
                case "particles_manifest.txt" -> txt;
                case "particles/smoke.txt" -> smoke;
                default -> null;
            });
        assertTrue(reg.build());
        assertEquals("particles/smoke.txt", reg.systemNameForId(1));
        assertEquals(1, reg.idForSystem("particles/smoke.txt"));
    }

    @Test
    void missingManifestFallsBackAndReports() {
        ParticleIdRegistry reg = new ParticleIdRegistry(k -> null);
        assertFalse(reg.build());          // 不抛异常
        assertTrue(reg.failureReported());
        assertNull(reg.systemNameForId(1)); // 回退：名字查找路径不受影响
    }
}
