package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public class ChatS2CPacket {
    public final UUID npcUuid;
    public final String reply;
    public final String emotion;   // happy/angry/sad/neutral/scared
    public final String gesture;   // wave/nod/shake/point/idle

    public ChatS2CPacket(UUID npcUuid, String reply, String emotion, String gesture) {
        this.npcUuid = npcUuid;
        this.reply = reply;
        this.emotion = emotion;
        this.gesture = gesture;
    }

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
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null) return;
            var level = mc.player.level();
            // ClientLevel doesn't expose getEntity(UUID) publicly, so iterate
            net.minecraft.world.entity.Entity foundEntity = null;
            if (level instanceof net.minecraft.client.multiplayer.ClientLevel clientLevel) {
                for (var e : clientLevel.entitiesForRendering()) {
                    if (e.getUUID().equals(packet.npcUuid)) {
                        foundEntity = e;
                        break;
                    }
                }
            }
            if (foundEntity instanceof transferstation.transferstation_whimsicalideas.client.model.NpcEntity npc) {
                // Forward to active chat screen (NpcChatScreen — will be created in task 2)
                if (mc.screen instanceof transferstation.transferstation_whimsicalideas.client.NpcChatScreen screen) {
                    screen.onNpcReply(packet.reply, packet.emotion, packet.gesture);
                }
                // Apply emotion/gesture on NPC
                npc.handleGesture(packet.emotion, packet.gesture);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
