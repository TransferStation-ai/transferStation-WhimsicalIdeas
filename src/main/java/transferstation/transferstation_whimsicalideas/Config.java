package transferstation.transferstation_whimsicalideas;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

import java.util.List;

@Mod.EventBusSubscriber(modid = Transferstation_whimsicalideas.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final List<String> DEFAULT_MESSAGES = java.util.Arrays.asList(
            "%entity%: Hello there!",
            "%entity%: Welcome to the world!",
            "%entity%: What a wonderful day!",
            "%entity%: Did you know... Minecraft is awesome?",
            "%entity%: Keep exploring!",
            "%entity%: Adventure awaits!",
            "%entity%: Stay curious, stay creative!",
            "%entity%: Building something cool?",
            "%entity%: The possibilities are endless!",
            "%entity%: Happy mining!",
            "%entity%: Have you tried the new features?",
            "%entity%: Remember to take breaks!",
            "%entity%: This world is yours to shape!",
            "%entity%: Every block tells a story",
            "%entity%: Creativity has no limits here",
            "%entity%: Dig deep, build high!",
            "%entity%: The nether calls...",
            "%entity%: End cities hold secrets",
            "%entity%: Redstone is magic",
            "%entity%: Enchanted gear makes you strong"
    );

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENTITY_MESSAGES = BUILDER
            .comment("Random messages that entities will say in chat. Use %entity% as a placeholder for the entity name.")
            .defineListAllowEmpty("entityMessages", DEFAULT_MESSAGES, o -> o instanceof String);

    public static final ForgeConfigSpec.ConfigValue<Integer> ENTITY_MESSAGE_INTERVAL = BUILDER
            .comment("Interval in seconds between entity messages")
            .defineInRange("entityMessageInterval", 60, 10, 600);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static List<? extends String> entityMessages;
    public static int entityMessageInterval;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        entityMessages = ENTITY_MESSAGES.get();
        entityMessageInterval = ENTITY_MESSAGE_INTERVAL.get();
    }
}
