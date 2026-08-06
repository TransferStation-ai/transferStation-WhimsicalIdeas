package transferstation.transferstation_whimsicalideas.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.NotNull;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.ModelPackage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class GmodModelScreen extends Screen {

    private static final int MODELS_PER_PAGE = 10;
    private static final int COLS = 5;
    private static final int ROWS = 2;
    private static final int MODEL_BTN_W = 52;
    private static final int MODEL_BTN_H = 90;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private int x;
    private int y;
    private int page;
    private int maxPage;
    private EditBox searchField;
    private String currentSearch = "";
    private boolean playerEnabled;
    private boolean mobEnabled;
    private boolean showFavoritesOnly = false;

    private List<ModelPackage> allPackages;
    private List<ModelPackage> filteredPackages;
    private Set<String> favoriteNames = new LinkedHashSet<>();

    protected GmodModelScreen() {
        super(Component.translatable("gui.transferstation_whimsicalideas.model_selection"));
        this.playerEnabled = GmodModelConfig.isPlayerModelEnabled();
        this.mobEnabled = GmodModelConfig.isMobModelEnabled();
        this.page = 0;
        loadFavorites();
        refreshPackages();
    }

    private void loadFavorites() {
        Path favFile = getFavoritesFile();
        if (favFile != null && Files.exists(favFile)) {
            try {
                String json = Files.readString(favFile);
                String[] arr = GSON.fromJson(json, String[].class);
                if (arr != null) {
                    favoriteNames = new LinkedHashSet<>(Arrays.asList(arr));
                }
            } catch (Exception e) {
                favoriteNames = new LinkedHashSet<>();
            }
        }
    }

    private void saveFavorites() {
        Path favFile = getFavoritesFile();
        if (favFile == null) return;
        try {
            Files.createDirectories(favFile.getParent());
            Files.writeString(favFile, GSON.toJson(favoriteNames.toArray(new String[0])));
        } catch (IOException ignored) {}
    }

    private Path getFavoritesFile() {
        Path configDir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
                .resolve("transferstation_whimsicalideas");
        return configDir.resolve("favorite_models.json");
    }

    private void toggleFavorite(ModelPackage pkg) {
        if (favoriteNames.contains(pkg.getName())) {
            favoriteNames.remove(pkg.getName());
        } else {
            favoriteNames.add(pkg.getName());
        }
        saveFavorites();
    }

    private boolean isFavorite(ModelPackage pkg) {
        return favoriteNames.contains(pkg.getName());
    }

    private void refreshPackages() {
        allPackages = GmodModelConfig.scanModelPackages();
        applyFilter();
    }

    private void applyFilter() {
        String search = currentSearch.toLowerCase(Locale.ROOT);
        if (search.isEmpty() && !showFavoritesOnly) {
            filteredPackages = new ArrayList<>(allPackages);
        } else {
            filteredPackages = allPackages.stream()
                    .filter(p -> {
                        if (showFavoritesOnly && !isFavorite(p)) return false;
                        if (search.isEmpty()) return true;
                        return p.getName().toLowerCase(Locale.ROOT).contains(search)
                                || (p.getDisplayName() != null && p.getDisplayName().toLowerCase(Locale.ROOT).contains(search))
                                || (p.getAuthor() != null && p.getAuthor().toLowerCase(Locale.ROOT).contains(search))
                                || p.getTags().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(search));
                    })
                    .collect(Collectors.toList());
        }
        maxPage = Math.max(0, (filteredPackages.size() - 1) / MODELS_PER_PAGE);
        if (page > maxPage) page = 0;
    }

    @Override
    protected void init() {
        this.clearWidgets();
        applyFilter();

        this.x = (width - 420) / 2;
        this.y = (height - 235) / 2;

        String prevText = searchField != null ? searchField.getValue() : currentSearch;
        boolean focused = searchField != null && searchField.isFocused();
        searchField = new EditBox(font, x + 144, y + 6, 140, 16, Component.translatable("gui.transferstation_whimsicalideas.search"));
        searchField.setValue(prevText);
        searchField.setTextColor(0xF3EFE0);
        searchField.setFocused(focused);
        searchField.moveCursorToEnd();
        addWidget(searchField);

        // Favorites toggle button
        addRenderableWidget(Button.builder(
                Component.literal(showFavoritesOnly ? "★ All" : "☆ Fav"),
                btn -> {
                    showFavoritesOnly = !showFavoritesOnly;
                    btn.setMessage(Component.literal(showFavoritesOnly ? "★ All" : "☆ Fav"));
                    page = 0;
                    init();
                }
        ).pos(x + 288, y + 6).size(36, 16).build());

        addRenderableWidget(Button.builder(
                Component.translatable(playerEnabled ? "gui.transferstation_whimsicalideas.player_on" : "gui.transferstation_whimsicalideas.player_off"),
                btn -> {
                    GmodModelConfig.togglePlayerModel();
                    playerEnabled = GmodModelConfig.isPlayerModelEnabled();
                    btn.setMessage(Component.translatable(playerEnabled ? "gui.transferstation_whimsicalideas.player_on" : "gui.transferstation_whimsicalideas.player_off"));
                }
        ).pos(x + 4, y + 210).size(62, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable(mobEnabled ? "gui.transferstation_whimsicalideas.mob_on" : "gui.transferstation_whimsicalideas.mob_off"),
                btn -> {
                    GmodModelConfig.toggleMobModel();
                    mobEnabled = GmodModelConfig.isMobModelEnabled();
                    btn.setMessage(Component.translatable(mobEnabled ? "gui.transferstation_whimsicalideas.mob_on" : "gui.transferstation_whimsicalideas.mob_off"));
                }
        ).pos(x + 68, y + 210).size(62, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.ai_config"),
                btn -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new AiConfigScreen());
                    }
                }
        ).pos(x + 4, y + 226).size(98, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.model_editor"),
                btn -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new transferstation.transferstation_whimsicalideas.client.editor.ModelEditorScreen(this));
                    }
                }
        ).pos(x + 106, y + 226).size(98, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.anim_editor"),
                btn -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new transferstation.transferstation_whimsicalideas.client.editor.AnimationEditorScreen(this));
                    }
                }
        ).pos(x + 208, y + 226).size(98, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.debug"),
                btn -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new transferstation.transferstation_whimsicalideas.client.debug.ModelDebugScreen(this));
                    }
                }
        ).pos(x + 310, y + 226).size(98, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.prev_page"),
                btn -> {
                    if (page > 0) {
                        page--;
                        init();
                    }
                }
        ).pos(x + 198, y + 215).size(52, 14).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.next_page"),
                btn -> {
                    if (page < maxPage) {
                        page++;
                        init();
                    }
                }
        ).pos(x + 308, y + 215).size(52, 14).build());

        int startIdx = page * MODELS_PER_PAGE;
        for (int i = 0; i < MODELS_PER_PAGE; i++) {
            int modelIndex = startIdx + i;
            if (modelIndex >= filteredPackages.size()) break;
            ModelPackage pkg = filteredPackages.get(modelIndex);
            int col = i % COLS;
            int row = i / COLS;
            int bx = x + 143 + col * (MODEL_BTN_W + 3);
            int by = y + 28 + row * (MODEL_BTN_H + 3);
            addRenderableWidget(new ModelSelectButton(bx, by, MODEL_BTN_W, MODEL_BTN_H, pkg, this));
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);

        graphics.fillGradient(x, y, x + 135, y + 235, 0xff_222222, 0xff_222222);
        graphics.fillGradient(x + 138, y, x + 420, y + 235, 0xff_222222, 0xff_222222);
        graphics.fillGradient(x + 351, y + 7, x + 352, y + 21, 0xFF_F3EFE0, 0xFF_F3EFE0);

        searchField.render(graphics, mouseX, mouseY, partialTicks);

        if (minecraft != null && minecraft.player != null) {
            com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
            double scale = window.getGuiScale();
            int scissorX = (int) ((x + 5) * scale);
            int scissorY = (int) (window.getHeight() - ((y + 200) * scale));
            int scissorW = (int) (125 * scale);
            int scissorH = (int) (171 * scale);
            RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);
            try {
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics, x + 67, y + 190, 70,
                        x + 67 - mouseX, y + 180 - 95 - mouseY, minecraft.player);
            } finally {
                RenderSystem.disableScissor();
            }
        }

        String currentModel = GmodModelConfig.getSelectedModelName();
        if (currentModel != null && !currentModel.isEmpty()) {
            List<FormattedCharSequence> split = font.split(Component.literal(currentModel), 125);
            int lineY = y + 205;
            for (FormattedCharSequence line : split) {
                int nameWidth = font.width(line);
                graphics.drawString(font, line, x + (135 - nameWidth) / 2, lineY, 0xF3EFE0);
                lineY += 10;
            }
        } else {
            graphics.drawCenteredString(font, Component.translatable("gui.transferstation_whimsicalideas.no_model"), x + 67, y + 205, 0x777777);
        }

        if (searchField.getValue().isEmpty() && !searchField.isFocused()) {
            graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.search_hint"), x + 148, y + 10, 0x777777);
        }

        // Favorites count indicator
        if (showFavoritesOnly) {
            graphics.drawString(font, filteredPackages.size() + " favorites", x + 148, y + 24, 0xFFD700);
        }

        String pageInfo = String.format("%d/%d", page + 1, maxPage + 1);
        graphics.drawString(font, pageInfo, x + 138 + (282 - font.width(pageInfo)) / 2, y + 223 - font.lineHeight / 2, 0xF3EFE0);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public void resize(@NotNull Minecraft minecraft, int width, int height) {
        String value = searchField != null ? searchField.getValue() : "";
        super.resize(minecraft, width, height);
        if (searchField != null) {
            searchField.setValue(value);
        }
    }

    @Override
    public void tick() {
        searchField.tick();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (searchField.mouseClicked(mouseX, mouseY, button)) {
            setFocused(searchField);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (searchField == null || !searchField.isFocused()) return false;
        String prev = searchField.getValue();
        if (searchField.charTyped(codePoint, modifiers)) {
            if (!Objects.equals(prev, searchField.getValue())) {
                currentSearch = searchField.getValue();
                page = 0;
                init();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchField == null) return super.keyPressed(keyCode, scanCode, modifiers);

        String prev = searchField.getValue();
        if (searchField.isFocused() && searchField.keyPressed(keyCode, scanCode, modifiers)) {
            if (!Objects.equals(prev, searchField.getValue())) {
                currentSearch = searchField.getValue();
                page = 0;
                init();
            }
            return true;
        }

        if (searchField.isFocused() && searchField.isVisible() && keyCode != 256) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (minecraft == null) return false;
        if (delta != 0 && mouseX >= (x + 143) && mouseX <= (x + 420) && mouseY >= (y + 25) && mouseY <= (y + 235)) {
            if (delta > 0 && page > 0) {
                page--;
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                init();
            } else if (delta < 0 && page < maxPage) {
                page++;
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                init();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    static class ModelSelectButton extends Button {
        private final ModelPackage pkg;
        private final GmodModelScreen parent;
        private final ResourceLocation iconLoc;

        ModelSelectButton(int x, int y, int w, int h, ModelPackage pkg, GmodModelScreen parent) {
            super(x, y, w, h, Component.literal(pkg.getDisplayName()), btn -> {}, DEFAULT_NARRATION);
            this.pkg = pkg;
            this.parent = parent;
            this.iconLoc = ModelLoadManager.loadEntityIcon(pkg.getPackageDir(), pkg.getName());
        }

        @Override
        public void onPress() {
            GmodModelConfig.setSelectedModelName(pkg.getName());
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int bgColor = isSelected() ? 0xFF_5A5A5A : 0xFF_434242;
            graphics.fillGradient(getX(), getY(), getX() + width, getY() + height, bgColor, bgColor);

            Font mcFont = Minecraft.getInstance().font;

            // Favorite star indicator
            boolean isFav = parent.isFavorite(pkg);
            if (isFav) {
                graphics.drawString(mcFont, "★", getX() + 2, getY() + 2, 0xFFD700);
            }

            if (iconLoc != null) {
                int iconSize = Math.min(width - 8, 56);
                int iconX = getX() + (width - iconSize) / 2;
                int iconY = getY() + 4;
                graphics.blit(iconLoc, iconX, iconY, iconSize, iconSize, 0, 0, 64, 64, 64, 64);

                Component displayName = Component.literal(pkg.getDisplayName());
                List<FormattedCharSequence> split = mcFont.split(displayName, width - 4);
                int nameY = getY() + height - 10 - (split.size() > 1 ? 9 : 0);
                if (split.size() > 1) {
                    graphics.drawCenteredString(mcFont, split.get(0), getX() + width / 2, nameY, 0xF3EFE0);
                    graphics.drawCenteredString(mcFont, split.get(1), getX() + width / 2, nameY + 9, 0xF3EFE0);
                } else {
                    graphics.drawCenteredString(mcFont, displayName, getX() + width / 2, nameY, 0xF3EFE0);
                }
            } else {
                Component displayName = Component.literal(pkg.getDisplayName());
                List<FormattedCharSequence> split = mcFont.split(displayName, width - 4);
                if (split.size() > 1) {
                    graphics.drawCenteredString(mcFont, split.get(0), getX() + width / 2, getY() + height - 19, 0xF3EFE0);
                    graphics.drawCenteredString(mcFont, split.get(1), getX() + width / 2, getY() + height - 10, 0xF3EFE0);
                } else {
                    graphics.drawCenteredString(mcFont, displayName, getX() + width / 2, getY() + height - 15, 0xF3EFE0);
                }
            }

            if (isHoveredOrFocused()) {
                graphics.fillGradient(getX(), getY() + 1, getX() + 1, getY() + height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
                graphics.fillGradient(getX(), getY(), getX() + width, getY() + 1, 0xff_F3EFE0, 0xff_F3EFE0);
                graphics.fillGradient(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1, 0xff_F3EFE0, 0xff_F3EFE0);
                graphics.fillGradient(getX(), getY() + height - 1, getX() + width, getY() + height, 0xff_F3EFE0, 0xff_F3EFE0);

                // Tooltip: author + favorite hint
                String tooltip = pkg.getAuthor() != null ? pkg.getAuthor() : "";
                tooltip += " [Right-click: favorite]";
                parent.setTooltipForNextRenderPass(Component.literal(tooltip));
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (isHovered && button == 1) { // Right-click = toggle favorite
                parent.toggleFavorite(pkg);
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                parent.init(); // Refresh to update star display
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        private boolean isSelected() {
            String selected = GmodModelConfig.getSelectedModelName();
            return selected != null && selected.equals(pkg.getName());
        }
    }
}
