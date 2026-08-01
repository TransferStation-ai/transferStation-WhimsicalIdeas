package transferstation.transferstation_whimsicalideas.client.particle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PcfParticleSystemDef {
    public final List<SystemDefinition> systemDefinitions = new ArrayList<>();

    public static class SystemDefinition {
        public String name;
        public int maxParticles = 1000;
        public float lifespan = 0f;      // 0 = per-particle lifetime
        public float emissionRate = 10f;
        public RendererDef renderer;
        public final List<InitializerDef> initializers = new ArrayList<>();
        public final List<OperatorDef> operators = new ArrayList<>();
        public final List<ChildDef> children = new ArrayList<>();
        public final List<ForceDef> forces = new ArrayList<>();
        public boolean continuous = false;  // continuous emission vs burst
    }

    public static class RendererDef {
        public RendererType type = RendererType.SPRITE;
        // Sprite
        public String materialPath;
        public int textureWidth = 64, textureHeight = 64;
        public boolean additive = false;
        // Model
        public String modelPath;
        public float modelScale = 1f;
        // Beam
        public float beamWidth = 2f;
        // Trail
        public float trailLength = 1f;
        public int trailSegments = 8;
        // Decal
        public String decalMaterial;
        public float decalSize = 16f;
        // Light
        public float lightRadius = 8f;
        public float lightIntensity = 1f;
        // Rope
        public float ropeWidth = 1f;
        public int ropeSegments = 16;
    }

    public enum RendererType {
        SPRITE, MODEL, BEAM, TRAIL, DECAL, LIGHT, ROPE
    }

    public static class InitializerDef {
        public String type;
        public Map<String, Object> params = new HashMap<>();
    }

    public static class OperatorDef {
        public String type;
        public Map<String, Object> params = new HashMap<>();
    }

    public static class ChildDef {
        public String childName;
        public float delay;
        public float delayRate;
    }

    public static class ForceDef {
        public String type;
        public float magnitude;
        public float[] direction = new float[3];
    }
}
