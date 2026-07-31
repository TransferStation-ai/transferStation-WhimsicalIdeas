package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import transferstation.transferstation_whimsicalideas.client.NpcChatScreen;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * @param emotion happy/angry/sad/neutral/scared
 * @param gesture wave/nod/shake/point/idle
 */
public record ChatS2CPacket(UUID npcUuid, String reply, String emotion, String gesture) {

    public static ChatS2CPacket decode(FriendlyByteBuf buf) {
        return new ChatS2CPacket(
                buf.readUUID(),
                buf.readUtf(512),
                buf.readUtf(32),
                buf.readUtf(32)
        );
    }

    public static void encode(ChatS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.reply, 512);
        buf.writeUtf(packet.emotion, 32);
        buf.writeUtf(packet.gesture, 32);
    }

    public static void handle(ChatS2CPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            var level = mc.player.level();
            // ClientLevel doesn't expose getEntity(UUID) publicly, so iterate
            Entity foundEntity = null;
            if (level instanceof ClientLevel clientLevel) {
                for (var e : clientLevel.entitiesForRendering()) {
                    if (e.getUUID().equals(packet.npcUuid)) {
                        foundEntity = e;
                        break;
                    }
                }
            }
            if (foundEntity instanceof NpcEntity npc) {
                // Forward to active chat screen (NpcChatScreen — will be created in task 2)
                if (mc.screen instanceof NpcChatScreen screen) {
                    screen.onNpcReply(packet.reply, packet.emotion, packet.gesture);
                }
                // Apply emotion/gesture on NPC
                npc.handleGesture(packet.emotion, packet.gesture);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
