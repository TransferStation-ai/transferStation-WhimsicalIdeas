package transferstation.transferstation_whimsicalideas.client.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NpcChatHandlerTest {

    @Test
    void parsePoseBonesExtractsValidPose() {
        var json = JsonParser.parseString("{\"pose\": {\"bones\": {" +
                "\"ValveBiped.Bip01_Head\": [0, 0.5, 0]," +
                "\"ValveBiped.Bip01_R_UpperArm\": [-0.8, 0, 0]}, " +
                "\"duration\": 2.0}}").getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(2, bones.size());
        assertEquals(0.5f, bones.get("ValveBiped.Bip01_Head")[1], 0.001f);
        assertEquals(-0.8f, bones.get("ValveBiped.Bip01_R_UpperArm")[0], 0.001f);
    }

    @Test
    void parsePoseBonesIgnoresMalformedEntries() {
        var json = JsonParser.parseString("{\"pose\": {\"bones\": {" +
                "\"good\": [1, 2, 3]," +
                "\"tooShort\": [1]," +
                "\"notArray\": \"oops\"," +
                "\"notNumber\": [\"a\", 2, 3]}, " +
                "\"duration\": 1.0}}").getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(1, bones.size());
        assertTrue(bones.containsKey("good"));
    }

    @Test
    void parsePoseBonesTruncatesAtEight() {
        StringBuilder sb = new StringBuilder("{\"pose\": {\"bones\": {");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"bone").append(i).append("\": [0, 0, ").append(i).append("]");
        }
        sb.append("}, \"duration\": 1.0}}");
        var json = JsonParser.parseString(sb.toString()).getAsJsonObject();
        Map<String, float[]> bones = NpcChatHandler.parsePoseBones(json.getAsJsonObject("pose").getAsJsonObject("bones"));
        assertEquals(8, bones.size());
    }
}
