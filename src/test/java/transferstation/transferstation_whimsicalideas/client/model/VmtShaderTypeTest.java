package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VmtShaderTypeTest {

    @Test
    void fromNameRoutesAllShaders() {
        assertEquals(VmtParser.ShaderType.VERTEX_LIT_GENERIC, VmtParser.ShaderType.fromName("VertexLitGeneric"));
        assertEquals(VmtParser.ShaderType.UNLIT_GENERIC, VmtParser.ShaderType.fromName("UnlitGeneric"));
        assertEquals(VmtParser.ShaderType.EYE_REFRACT, VmtParser.ShaderType.fromName("EyeRefract"));
        assertEquals(VmtParser.ShaderType.SPRITE, VmtParser.ShaderType.fromName("Sprite"));
        assertEquals(VmtParser.ShaderType.CABLE, VmtParser.ShaderType.fromName("Cable"));
        assertEquals(VmtParser.ShaderType.SKYBOX, VmtParser.ShaderType.fromName("SkyBox"));
        assertEquals(VmtParser.ShaderType.TOOL_TEXTURE, VmtParser.ShaderType.fromName("ToolTexture"));
        assertEquals(VmtParser.ShaderType.TOOL_TEXTURE, VmtParser.ShaderType.fromName("tools/toolsskybox"));
    }

    @Test
    void fromNameHandlesCaseAndNull() {
        assertEquals(VmtParser.ShaderType.UNLIT_GENERIC, VmtParser.ShaderType.fromName("unlitgeneric"));
        assertEquals(VmtParser.ShaderType.UNKNOWN, VmtParser.ShaderType.fromName("SomeCustomShader"));
        assertEquals(VmtParser.ShaderType.UNKNOWN, VmtParser.ShaderType.fromName(null));
    }
}
