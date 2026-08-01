package transferstation.transferstation_whimsicalideas.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import transferstation.transferstation_whimsicalideas.client.NpcChatScreen;
import transferstation.transferstation_whimsicalideas.client.model.NpcEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * @param emotion happy/angry/sad/neutral/scared
 * @param gesture wave/nod/shake/point/idle
 * @param pose 骨骼名 → [rx, ry, rz]（弧度，可为 null）
 * @param poseDuration pose 保持秒数
 */
public record ChatS2CPacket(UUID npcUuid, String reply, String emotion, String gesture,
                            Map<String, float[]> pose, float poseDuration) {

    public static ChatS2CPacket decode(FriendlyByteBuf buf) {
        Map<String, float[]> pose = new HashMap<>();
        int count = buf.readInt();
        for (int i = 0; i < count; i++) {
            String boneName = buf.readUtf(64);
            int len = buf.readInt();
            float[] r = new float[len];
            for (int j = 0; j < len; j++) {
                r[j] = buf.readFloat();
            }
            pose.put(boneName, r);
        }
        return new ChatS2CPacket(
                buf.readUUID(),
                buf.readUtf(512),
                buf.readUtf(32),
                buf.readUtf(32),
                pose,
                buf.readFloat()
        );
    }

    public static void encode(ChatS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.pose != null ? packet.pose.size() : 0);
        if (packet.pose != null) {
            for (Map.Entry<String, float[]> entry : packet.pose.entrySet()) {
                buf.writeUtf(entry.getKey(), 64);
                float[] r = entry.getValue();
                buf.writeInt(r.length);
                for (float v : r) {
                    buf.writeFloat(v);
                }
            }
        }
        buf.writeUUID(packet.npcUuid);
        buf.writeUtf(packet.reply, 512);
        buf.writeUtf(packet.emotion, 32);
        buf.writeUtf(packet.gesture, 32);
        buf.writeFloat(packet.poseDuration);
    }

    public static void handle(ChatS2CPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return;
            var level = mc.player.level();
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
                if (mc.screen instanceof NpcChatScreen screen) {
                    screen.onNpcReply(packet.reply, packet.emotion, packet.gesture);
                }
                npc.handleGesture(packet.emotion, packet.gesture);
                if (packet.pose != null && !packet.pose.isEmpty()) {
                    npc.applyBonePose(packet.pose, packet.poseDuration);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
