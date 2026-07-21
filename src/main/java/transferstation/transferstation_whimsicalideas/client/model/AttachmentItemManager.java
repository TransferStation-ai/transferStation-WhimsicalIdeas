package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AttachmentItemManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Map from model name -> list of attachment item info
    private static final Map<String, List<AttachmentItemInfo>> modelAttachments = new ConcurrentHashMap<>();

    public static class AttachmentItemInfo {
        public final String name;           // Attachment name from MDL
        public final String itemId;         // Registry ID
        public final String modelName;      // Source model name
        public final int boneIndex;         // Bone this attaches to
        public final float[] position;      // Local position (org)
        public final float[] rotation;      // Local rotation
        public ResourceLocation iconTexture; // Display icon

        public AttachmentItemInfo(String name, String itemId, String modelName,
                                  int boneIndex, float[] position, float[] rotation) {
            this.name = name;
            this.itemId = itemId;
            this.modelName = modelName;
            this.boneIndex = boneIndex;
            this.position = position;
            this.rotation = rotation;
        }
    }

    /** Called when a model is loaded to register attachment items */
    public static void registerAttachments(String modelName, SourceModelData modelData) {
        if (modelData.attachments == null || modelData.attachments.isEmpty()) return;

        List<AttachmentItemInfo> items = new ArrayList<>();
        for (MdlDataTypes.Attachment att : modelData.attachments) {
            if (att.name == null || att.name.isEmpty()) continue;

            String safeName = att.name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase(Locale.ROOT);
            String itemId = "attachment_" + modelName.replace('/', '_') + "_" + safeName;

            AttachmentItemInfo info = new AttachmentItemInfo(
                att.name, itemId, modelName,
                att.attachmentbone,
                att.org != null ? att.org.clone() : new float[3],
                att.rot != null ? att.rot.clone() : null
            );

            // Try to derive icon from model's first texture
            if (!modelData.meshes.isEmpty() && modelData.meshes.get(0).texture != null) {
                info.iconTexture = modelData.meshes.get(0).texture;
            }

            items.add(info);
            LOGGER.info("[AttachmentItemManager] Registered attachment item '{}' for model '{}' (bone={})",
                itemId, modelName, att.attachmentbone);
        }

        if (!items.isEmpty()) {
            modelAttachments.put(modelName, items);
        }
    }

    public static List<AttachmentItemInfo> getAttachments(String modelName) {
        return modelAttachments.getOrDefault(modelName, Collections.emptyList());
    }

    public static Map<String, List<AttachmentItemInfo>> getAllAttachments() {
        return Collections.unmodifiableMap(modelAttachments);
    }

    public static void clearModel(String modelName) {
        modelAttachments.remove(modelName);
    }

    public static void clearAll() {
        modelAttachments.clear();
    }
}
