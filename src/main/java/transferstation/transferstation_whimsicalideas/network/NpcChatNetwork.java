package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import transferstation.transferstation_whimsicalideas.Transferstation_whimsicalideas;

public class NpcChatNetwork {
    private static final String PROTOCOL_VERSION = "1.0";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder.named(
            ResourceLocation.parse(Transferstation_whimsicalideas.MODID + ":npc_chat"))
        .networkProtocolVersion(() -> PROTOCOL_VERSION)
        .clientAcceptedVersions(s -> true)
        .serverAcceptedVersions(s -> true)
        .simpleChannel();

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(ChatC2SPacket.class, id++)
            .encoder(ChatC2SPacket::encode)
            .decoder(ChatC2SPacket::decode)
            .consumerNetworkThread(ChatC2SPacket::handle)
            .add();
        CHANNEL.messageBuilder(ChatS2CPacket.class, id++)
            .encoder(ChatS2CPacket::encode)
            .decoder(ChatS2CPacket::decode)
            .consumerNetworkThread(ChatS2CPacket::handle)
            .add();
    }
}
