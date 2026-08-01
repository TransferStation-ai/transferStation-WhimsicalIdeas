package transferstation.transferstation_whimsicalideas.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.List;

public class AttachmentItem extends Item {

    private final String modelName;
    private final String attachmentName;

    public AttachmentItem(Properties properties, String modelName, String attachmentName) {
        super(properties);
        this.modelName = modelName;
        this.attachmentName = attachmentName;
    }

    public String getModelName() { return modelName; }
    public String getAttachmentName() { return attachmentName; }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level level, List<Component> tooltip, @NotNull TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.transferstation_whimsicalideas.attachment_item",
            attachmentName, modelName));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
