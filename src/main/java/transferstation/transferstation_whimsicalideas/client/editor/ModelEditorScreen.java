package transferstation.transferstation_whimsicalideas.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.nio.file.Path;
import java.util.List;

/**
 * In-game 3D model editor for Source/MDL models.
 *
 * Features:
 *  - Orbit/zoom/pan viewport (see {@link ModelViewport})
 *  - Bone list + select; edit selected bone local position/rotation
 *  - Material panel: edit color tint, alpha, cull, translucency per mesh
 *  - Save/load overrides to a JSON file next to the model package
 */
@OnlyIn(Dist.CLIENT)
public class ModelEditorScreen extends Screen {

    private final Screen parent;
    private ModelViewport viewport = new ModelViewport();
    private SourceModelData model;
    private String modelName;

    private int selectedBone = -1;
    private int selectedMesh = -1;

    private int boneListScroll = 0;
    private final int visibleBoneRows = 18;

    private EditBox bonePosX, bonePosY, bonePosZ;
    private EditBox boneRotX, boneRotY, boneRotZ;

    private EditBox tintR, tintG, tintB, tintA;

    private boolean dragging = false;
    private double lastMouseX, lastMouseY;
    private boolean panning = false;

    private Component statusMsg = null;
    private int statusTimer = 0;

    public ModelEditorScreen(Screen parent) {
        super(Component.translatable("gui.transferstation_whimsicalideas.model_editor"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();

        if (model == null) {
            modelName = GmodModelConfig.getSelectedModelName();
            if (modelName == null || modelName.isEmpty()) {
                var pkgs = GmodModelConfig.scanModelPackages();
                if (!pkgs.isEmpty()) modelName = pkgs.get(0).getName();
            }
            if (modelName != null && !modelName.isEmpty()) {
                Path pkgDir = resolvePackageDir(modelName);
                if (pkgDir != null) {
                    model = ModelLoadManager.loadModel(pkgDir);
                }
            }
            if (model != null) {
                viewport.setModel(model);
            }
        }

        int vpX = 10, vpY = 30;
        int vpW = Math.max(200, width - 360);
        int vpH = height - 60;
        viewport.setRect(vpX, vpY, vpW, vpH);

        int panelX = vpX + vpW + 10;
        int panelW = width - panelX - 10;

        int ey = 40;
        bonePosX = makeNumBox(panelX, ey, "0", panelW);
        bonePosY = makeNumBox(panelX, ey + 20, "0", panelW);
        bonePosZ = makeNumBox(panelX, ey + 40, "0", panelW);
        boneRotX = makeNumBox(panelX, ey + 70, "0", panelW);
        boneRotY = makeNumBox(panelX, ey + 90, "0", panelW);
        boneRotZ = makeNumBox(panelX, ey + 110, "0", panelW);

        int my = ey + 160;
        tintR = makeNumBox(panelX, my, "1", panelW);
        tintG = makeNumBox(panelX, my + 20, "1", panelW);
        tintB = makeNumBox(panelX, my + 40, "1", panelW);
        tintA = makeNumBox(panelX, my + 60, "1", panelW);

        int by = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_save"),
                btn -> saveOverrides()).pos(panelX, by).size(panelW / 2 - 4, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_reset"),
                btn -> resetOverrides()).pos(panelX + panelW / 2 + 4, by).size(panelW / 2 - 4, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.back"),
                btn -> onClose()).pos(10, 6).size(60, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_apply_bone"),
                btn -> applyBoneEdit()).pos(panelX, ey + 135).size(panelW, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_apply_mat"),
                btn -> applyMaterialEdit()).pos(panelX, my + 85).size(panelW, 18).build());

        refreshBoneFields();
        refreshMaterialFields();
    }

    private EditBox makeNumBox(int x, int y, String def, int w) {
        EditBox box = new EditBox(font, x, y, w, 16, Component.literal(def));
        box.setValue(def);
        box.setTextColor(0xF3EFE0);
        addWidget(box);
        return box;
    }

    private void refreshBoneFields() {
        if (model == null || selectedBone < 0 || selectedBone >= model.bones.size()) return;
        SourceModelData.BoneInfo bone = model.bones.get(selectedBone);
        float[] p = bone.pos != null ? bone.pos : new float[]{0, 0, 0};
        bonePosX.setValue(fmt(p[0])); bonePosY.setValue(fmt(p[1])); bonePosZ.setValue(fmt(p[2]));
        float[] r = bone.rot != null ? bone.rot : new float[]{0, 0, 0};
        boneRotX.setValue(fmt(r[0])); boneRotY.setValue(fmt(r[1])); boneRotZ.setValue(fmt(r[2]));
    }

    private void refreshMaterialFields() {
        if (model == null || selectedMesh < 0 || selectedMesh >= model.meshes.size()) return;
        SourceModelData.MeshData mesh = model.meshes.get(selectedMesh);
        float[] t = mesh.colorTint != null && mesh.colorTint.length >= 4
                ? mesh.colorTint : new float[]{1, 1, 1, mesh.alpha};
        tintR.setValue(fmt(t[0])); tintG.setValue(fmt(t[1])); tintB.setValue(fmt(t[2]));
        tintA.setValue(fmt(t.length >= 4 ? t[3] : mesh.alpha));
    }

    private static String fmt(float v) {
        return String.format("%.3f", v);
    }

    private void applyBoneEdit() {
        if (model == null || selectedBone < 0) return;
        float[] p = new float[]{parse(bonePosX), parse(bonePosY), parse(bonePosZ)};
        float[] r = new float[]{parse(boneRotX), parse(boneRotY), parse(boneRotZ)};
        viewport.setBoneOverride(selectedBone, new ModelViewport.BoneOverride(p, r));
        setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_bone_applied",
                model.bones.get(selectedBone).name));
    }

    private void applyMaterialEdit() {
        if (model == null || selectedMesh < 0) return;
        SourceModelData.MeshData mesh = model.meshes.get(selectedMesh);
        mesh.colorTint = new float[]{parse(tintR), parse(tintG), parse(tintB), parse(tintA)};
        mesh.alpha = parse(tintA);
        setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_mat_applied", selectedMesh));
    }

    private static float parse(EditBox box) {
        try { return Float.parseFloat(box.getValue().trim()); }
        catch (Exception e) { return 0f; }
    }

    private void saveOverrides() {
        if (model == null || modelName == null) return;
        Path out = ModelEditorIO.saveModelOverrides(modelName, model, viewport);
        if (out != null) {
            setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_saved", out.getFileName().toString()));
        } else {
            setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_save_failed"));
        }
    }

    private void resetOverrides() {
        if (model == null) return;
        viewport.clearBoneOverrides();
        Path pkgDir = resolvePackageDir(modelName);
        if (pkgDir != null) {
            SourceModelData fresh = ModelLoadManager.loadModel(pkgDir);
            if (fresh != null) {
                model = fresh;
                viewport.setModel(fresh);
            }
        }
        selectedBone = -1; selectedMesh = -1;
        setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_reset_done"));
    }

    private void setStatus(Component msg) {
        this.statusMsg = msg;
        this.statusTimer = 120;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);

        if (model == null) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.transferstation_whimsicalideas.editor_no_model"),
                    width / 2, height / 2, 0xFF5555);
            super.render(graphics, mouseX, mouseY, partialTicks);
            return;
        }

        viewport.render(graphics.pose());

        drawBoneGizmos(graphics);

        int panelX = viewport.getX() + viewport.getWidth() + 10;
        int panelW = width - panelX - 10;
        graphics.fillGradient(panelX, 30, panelX + panelW, height - 30, 0xFF_222222, 0xFF_222222);

        int listY = 30;
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_bones"),
                panelX + 4, listY, 0xF3EFE0);
        int rowH = 14;
        int start = boneListScroll;
        int end = Math.min(model.bones.size(), start + visibleBoneRows);
        for (int i = start; i < end; i++) {
            int ry = listY + 14 + (i - start) * rowH;
            boolean sel = i == selectedBone;
            if (sel) graphics.fillGradient(panelX + 2, ry, panelX + panelW - 2, ry + rowH - 1, 0xFF_5A5A5A, 0xFF_5A5A5A);
            String name = model.bones.get(i).name;
            if (name.length() > 28) name = name.substring(0, 28);
            graphics.drawString(font, name, panelX + 6, ry + 2, sel ? 0xFFFFFF : 0xCCCCCC);
        }

        int ey = 40;
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_pos"), panelX, ey - 12, 0xAAAAAA);
        graphics.drawString(font, "X", panelX, ey + 4, 0x88CCFF);
        graphics.drawString(font, "Y", panelX, ey + 24, 0x88CCFF);
        graphics.drawString(font, "Z", panelX, ey + 44, 0x88CCFF);
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_rot"), panelX, ey + 58, 0xAAAAAA);
        graphics.drawString(font, "X", panelX, ey + 74, 0xFFCC88);
        graphics.drawString(font, "Y", panelX, ey + 94, 0xFFCC88);
        graphics.drawString(font, "Z", panelX, ey + 114, 0xFFCC88);

        int my = ey + 160;
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_material"), panelX, my - 14, 0xAAAAAA);
        graphics.drawString(font, "R", panelX, my + 4, 0xFF8888);
        graphics.drawString(font, "G", panelX, my + 24, 0x88FF88);
        graphics.drawString(font, "B", panelX, my + 44, 0x8888FF);
        graphics.drawString(font, "A", panelX, my + 64, 0xCCCCCC);
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_mesh", selectedMesh >= 0 ? selectedMesh : -1),
                panelX, my + 110, 0xAAAAAA);

        if (statusMsg != null && statusTimer > 0) {
            graphics.drawString(font, statusMsg, 12, height - 48, 0x88FF88);
            statusTimer--;
        }

        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.model_editor") + ": " + modelName,
                76, 10, 0xF3EFE0);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void drawBoneGizmos(GuiGraphics graphics) {
        if (model == null) return;
        for (int i = 0; i < model.bones.size(); i++) {
            var wp = viewport.boneWorldPos(i);
            if (wp == null) continue;
            int[] scr = viewport.projectToScreen(wp);
            if (scr == null) continue;
            boolean sel = i == selectedBone;
            int color = sel ? 0xFFFFAA00 : 0xFF66CCFF;
            graphics.fillGradient(scr[0] - 2, scr[1] - 2, scr[0] + 2, scr[1] + 2, color, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (model == null) return super.mouseClicked(mouseX, mouseY, button);

        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            int hit = pickBone((int) mouseX, (int) mouseY);
            if (hit >= 0) {
                selectedBone = hit;
                selectedMesh = -1;
                refreshBoneFields();
                return true;
            }
            if (button == 1) { panning = true; lastMouseX = mouseX; lastMouseY = mouseY; return true; }
            dragging = true; lastMouseX = mouseX; lastMouseY = mouseY;
            return true;
        }

        int panelX = viewport.getX() + viewport.getWidth() + 10;
        if (mouseX >= panelX && mouseX <= panelX + (width - panelX - 10)) {
            int listY = 30 + 14;
            int rowH = 14;
            if (mouseY >= listY && mouseY <= listY + visibleBoneRows * rowH) {
                int idx = boneListScroll + (int) ((mouseY - listY) / rowH);
                if (idx >= 0 && idx < model.bones.size()) {
                    selectedBone = idx;
                    selectedMesh = -1;
                    refreshBoneFields();
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int pickBone(int mx, int my) {
        int best = -1; int bestDist = 12 * 12;
        for (int i = 0; i < model.bones.size(); i++) {
            var wp = viewport.boneWorldPos(i);
            if (wp == null) continue;
            int[] scr = viewport.projectToScreen(wp);
            if (scr == null) continue;
            int dx = scr[0] - mx, dy = scr[1] - my;
            int d = dx * dx + dy * dy;
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        panning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) {
            viewport.orbit((float) (dx * 0.01), (float) (dy * 0.01));
            return true;
        }
        if (panning) {
            viewport.pan((float) dx, (float) dy);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            viewport.zoom((float) (delta > 0 ? -0.1 : 0.1));
            return true;
        }
        int panelX = viewport.getX() + viewport.getWidth() + 10;
        if (mouseX >= panelX && model != null) {
            int maxScroll = Math.max(0, model.bones.size() - visibleBoneRows);
            boneListScroll = Math.max(0, Math.min(maxScroll, boneListScroll + (delta > 0 ? -1 : 1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private static Path resolvePackageDir(String name) {
        for (var pkg : GmodModelConfig.scanModelPackages()) {
            if (pkg.getName().equals(name)) return pkg.getPackageDir();
        }
        return null;
    }
}
