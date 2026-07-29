package transferstation.transferstation_whimsicalideas;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebugConfigTest {

    @Test
    void defaultsAreFalse() {
        assertFalse(DebugConfig.isStrictParsing());
        assertFalse(DebugConfig.isDebugLogging());
    }

    @Test
    void setStrictParsing() {
        DebugConfig.setStrictParsing(true);
        assertTrue(DebugConfig.isStrictParsing());
        DebugConfig.setStrictParsing(false);
        assertFalse(DebugConfig.isStrictParsing());
    }

    @Test
    void setDebugLogging() {
        DebugConfig.setDebugLogging(true);
        assertTrue(DebugConfig.isDebugLogging());
        DebugConfig.setDebugLogging(false);
        assertFalse(DebugConfig.isDebugLogging());
    }

    @Test
    void toggleStrictParsing() {
        boolean before = DebugConfig.isStrictParsing();
        DebugConfig.toggleStrictParsing();
        assertNotEquals(before, DebugConfig.isStrictParsing());
        DebugConfig.toggleStrictParsing();
        assertEquals(before, DebugConfig.isStrictParsing());
    }

    @Test
    void toggleDebugLogging() {
        boolean before = DebugConfig.isDebugLogging();
        DebugConfig.toggleDebugLogging();
        assertNotEquals(before, DebugConfig.isDebugLogging());
        DebugConfig.toggleDebugLogging();
        assertEquals(before, DebugConfig.isDebugLogging());
    }

    @Test
    void getStatus() {
        DebugConfig.setStrictParsing(false);
        DebugConfig.setDebugLogging(false);
        String status = DebugConfig.getStatus();
        assertTrue(status.contains("OFF"));
        assertTrue(status.contains("Strict"));
    }

    @Test
    void independentSettings() {
        DebugConfig.setStrictParsing(true);
        DebugConfig.setDebugLogging(false);
        assertTrue(DebugConfig.isStrictParsing());
        assertFalse(DebugConfig.isDebugLogging());

        DebugConfig.setStrictParsing(false);
        DebugConfig.setDebugLogging(true);
        assertFalse(DebugConfig.isStrictParsing());
        assertTrue(DebugConfig.isDebugLogging());
    }
}
