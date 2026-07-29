package transferstation.transferstation_whimsicalideas.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;

/**
 * Example GameTests for the mod.
 * Run with the "gameTestServer" Gradle run configuration.
 * These tests run inside a Minecraft server with the mod loaded.
 */
@GameTestHolder(Transferstation_whimsicalideas.MODID)
public class ExampleGameTest {

    @GameTest(template = "empty_test_structure", batch = "basic_tests")
    public static void exampleTest(GameTestHelper helper) {
        helper.succeed();
    }
}
