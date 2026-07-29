package transferstation.transferstation_whimsicalideas.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

public class ClientNotifications {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static long lastNotificationTime = 0;
    private static final long NOTIFICATION_COOLDOWN_MS = 2000;

    public static void showModelLoadStarted(String modelName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getToasts().addToast(new SystemToast(
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.translatable("notification.transferstation_whimsicalideas.loading_model"),
            Component.literal(modelName)
        ));
    }

    public static void showModelLoadComplete(String modelName, int meshes, int triangles) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getToasts().addToast(new SystemToast(
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.translatable("notification.transferstation_whimsicalideas.model_loaded", modelName),
            Component.translatable("notification.transferstation_whimsicalideas.mesh_info", meshes, triangles)
        ));
    }

    public static void showModelLoadError(String modelName, String error) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getToasts().addToast(new SystemToast(
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.translatable("notification.transferstation_whimsicalideas.load_error"),
            Component.translatable("notification.transferstation_whimsicalideas.error_format", modelName, error)
        ));
    }

    public static void showNpcSpawned(String modelName) {
        long now = System.currentTimeMillis();
        if (now - lastNotificationTime < NOTIFICATION_COOLDOWN_MS) return;
        lastNotificationTime = now;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getToasts().addToast(new SystemToast(
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.translatable("notification.transferstation_whimsicalideas.npc_spawned"),
            Component.translatable("notification.transferstation_whimsicalideas.model_name", modelName)
        ));
    }

    public static void showInfo(String title, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.getToasts().addToast(new SystemToast(
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.literal(title),
            Component.literal(message)
        ));
    }
}
