package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

import java.util.UUID;
import java.util.function.Supplier;

public record ChatC2SPacket(UUID npcUuid, String message) {

    public static ChatC2SPacket decode(FriendlyByteBuf buf) {
        return new ChatC2SPacket(buf.readUUID(), buf.readUtf(256));
    }

    public static void encode(ChatC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.message, 256);
    }

    public static void handle(ChatC2SPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender == null) return;
            // ServerPlayer.serverLevel() returns ServerLevel, which has getEntity(UUID)
            var level = sender.serverLevel();
            var entity = level.getEntity(packet.npcUuid);
            if (entity instanceof NpcEntity npc) {
                // Process on server thread
                npc.handleChatMessage(sender, packet.message);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
