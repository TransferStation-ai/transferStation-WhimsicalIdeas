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
import transferstation.transferstation_whimsicalideas.client.morph.MorphManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-game 3D model animation editor.
 *
 * Two editing modes (tabs):
 *  - BONE: keyframe bone local transforms on a timeline (pos + euler rot)
 *  - MORPH: blend expression/morph clips with per-track weights over time
 *
 * The result can be played back in the viewport and exported to a custom
 * animation JSON (loaded by {@link AnimationEditorIO}).
 */
@OnlyIn(Dist.CLIENT)
public class AnimationEditorScreen extends Screen {

    private final Screen parent;
    private ModelViewport viewport = new ModelViewport();
    private SourceModelData model;
    private String modelName;

    private final EditableAnimation anim = new EditableAnimation();
    private int currentFrame = 0;
    private boolean playing = false;
    private int playTick = 0;

    private enum Tab { BONE, MORPH }
    private Tab tab = Tab.BONE;

    private int selectedBone = -1;
    private final List<String> morphNames = new ArrayList<>();
    private int selectedMorph = -1;

    private EditBox kfPosX, kfPosY, kfPosZ, kfRotX, kfRotY, kfRotZ;
    private EditBox morphWeightBox;

    private boolean dragging = false;
    private double lastMouseX, lastMouseY;
    private boolean panning = false;

    private Component statusMsg = null;
    private int statusTimer = 0;

    public AnimationEditorScreen(Screen parent) {
        super(Component.translatable("gui.transferstation_whimsicalideas.anim_editor"));
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
                if (pkgDir != null) model = ModelLoadManager.loadModel(pkgDir);
            }
            if (model != null) viewport.setModel(model);
        }

        morphNames.clear();
        morphNames.addAll(MorphManager.getMorphNames());

        int vpX = 10, vpY = 30;
        int vpW = Math.max(200, width - 320);
        int vpH = height - 200;
        viewport.setRect(vpX, vpY, vpW, vpH);

        int panelX = vpX + vpW + 10;
        int panelW = width - panelX - 10;

        int ey = 40;
        kfPosX = numBox(panelX, ey, "0", panelW);
        kfPosY = numBox(panelX, ey + 20, "0", panelW);
        kfPosZ = numBox(panelX, ey + 40, "0", panelW);
        kfRotX = numBox(panelX, ey + 70, "0", panelW);
        kfRotY = numBox(panelX, ey + 90, "0", panelW);
        kfRotZ = numBox(panelX, ey + 110, "0", panelW);
        morphWeightBox = numBox(panelX, ey + 150, "1", panelW);

        int by = height - 26;
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_play"),
                btn -> { playing = !playing; playTick = 0; }).pos(panelX, by).size(panelW / 4 - 3, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_stop"),
                btn -> { playing = false; currentFrame = 0; applyFrame(); }).pos(panelX + panelW / 4 + 3, by).size(panelW / 4 - 3, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_add_kf"),
                btn -> addKeyframe()).pos(panelX + panelW / 2 + 6, by).size(panelW / 4 - 3, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_save"),
                btn -> saveAnim()).pos(panelX + 3 * panelW / 4 + 9, by).size(panelW / 4 - 3, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_tab_bone"),
                btn -> tab = Tab.BONE).pos(panelX, 6).size(panelW / 2 - 3, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.editor_tab_morph"),
                btn -> tab = Tab.MORPH).pos(panelX + panelW / 2 + 3, 6).size(panelW / 2 - 3, 16).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.transferstation_whimsicalideas.back"),
                btn -> onClose()).pos(10, 6).size(60, 18).build());

        refreshBoneFields();
    }

    private EditBox numBox(int x, int y, String def, int w) {
        EditBox b = new EditBox(font, x, y, w, 16, Component.literal(def));
        b.setValue(def); b.setTextColor(0xF3EFE0);
        addWidget(b);
        return b;
    }

    private void refreshBoneFields() {
        if (model == null || selectedBone < 0) return;
        BoneKey k = new BoneKey(selectedBone, currentFrame);
        EditableAnimation.BoneKeyframe kf = anim.boneKeys.get(k);
        float[] p = kf != null ? kf.pos : (model.bones.get(selectedBone).pos != null ? model.bones.get(selectedBone).pos : new float[]{0,0,0});
        float[] r = kf != null ? kf.rot : (model.bones.get(selectedBone).rot != null ? model.bones.get(selectedBone).rot : new float[]{0,0,0});
        kfPosX.setValue(f(p[0])); kfPosY.setValue(f(p[1])); kfPosZ.setValue(f(p[2]));
        kfRotX.setValue(f(r[0])); kfRotY.setValue(f(r[1])); kfRotZ.setValue(f(r[2]));
    }

    private static String f(float v) { return String.format("%.3f", v); }
    private static float pf(EditBox b) { try { return Float.parseFloat(b.getValue().trim()); } catch (Exception e) { return 0f; } }

    private void addKeyframe() {
        if (tab == Tab.BONE) {
            if (model == null || selectedBone < 0) { setStatus(err("gui.transferstation_whimsicalideas.editor_no_bone")); return; }
            BoneKey k = new BoneKey(selectedBone, currentFrame);
            anim.boneKeys.put(k, new EditableAnimation.BoneKeyframe(
                    new float[]{pf(kfPosX), pf(kfPosY), pf(kfPosZ)},
                    new float[]{pf(kfRotX), pf(kfRotY), pf(kfRotZ)}));
            setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_kf_added",
                    model.bones.get(selectedBone).name, currentFrame));
        } else {
            if (selectedMorph < 0 || selectedMorph >= morphNames.size()) { setStatus(err("gui.transferstation_whimsicalideas.editor_no_morph")); return; }
            MorphKey k = new MorphKey(morphNames.get(selectedMorph), currentFrame);
            anim.morphKeys.put(k, pf(morphWeightBox));
            setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_morph_added",
                    morphNames.get(selectedMorph), currentFrame));
        }
    }

    private Component err(String key) { return Component.translatable(key); }

    private void saveAnim() {
        Path pkgDir = resolvePackageDir(modelName);
        if (pkgDir == null) { setStatus(err("gui.transferstation_whimsicalideas.editor_save_failed")); return; }
        Path out = AnimationEditorIO.save(anim, modelName, pkgDir);
        if (out != null) setStatus(Component.translatable("gui.transferstation_whimsicalideas.editor_saved", out.getFileName().toString()));
        else setStatus(err("gui.transferstation_whimsicalideas.editor_save_failed"));
    }

    private void setStatus(Component m) { this.statusMsg = m; this.statusTimer = 120; }

    @Override
    public void tick() {
        if (playing) {
            playTick++;
            if (playTick >= anim.ticksPerFrame) {
                playTick = 0;
                currentFrame++;
                if (currentFrame >= anim.frameCount) currentFrame = 0;
                applyFrame();
            }
        }
    }

    private void applyFrame() {
        viewport.clearBoneOverrides();
        if (model == null) return;
        for (int bi = 0; bi < model.bones.size(); bi++) {
            float[] basePos = model.bones.get(bi).pos != null ? model.bones.get(bi).pos : new float[]{0,0,0};
            float[] baseRot = model.bones.get(bi).rot != null ? model.bones.get(bi).rot : new float[]{0,0,0};
            EditableAnimation.BoneKeyframe a = anim.boneKeys.get(new BoneKey(bi, nearestKeyBelow(bi, currentFrame)));
            EditableAnimation.BoneKeyframe b = anim.boneKeys.get(new BoneKey(bi, nearestKeyAbove(bi, currentFrame)));
            float[] pos = lerp(a != null ? a.pos : basePos, b != null ? b.pos : basePos, bi, currentFrame);
            float[] rot = lerp(a != null ? a.rot : baseRot, b != null ? b.rot : baseRot, bi, currentFrame);
            viewport.setBoneOverride(bi, new ModelViewport.BoneOverride(pos, rot));
        }
        Map<String, Float> activeMorphs = new LinkedHashMap<>();
        for (var e : anim.morphKeys.entrySet()) {
            MorphKey mk = e.getKey();
            float w = e.getValue();
            if (mk.frame <= currentFrame) {
                float prev = activeMorphs.getOrDefault(mk.morph, 0f);
                activeMorphs.put(mk.morph, Math.max(prev, w));
            }
        }
        for (var e : activeMorphs.entrySet()) {
            applyMorphToViewport(e.getKey(), e.getValue());
        }
    }

    private void applyMorphToViewport(String morphName, float weight) {
        var morph = MorphManager.getMorph(morphName);
        if (morph == null) return;
        for (var bd : morph.boneDataList) {
            int bi = boneIndexByName(bd.boneName);
            if (bi < 0) continue;
            var ov = viewport.getBoneOverride(bi);
            float[] p = ov != null ? ov.pos.clone() : (model.bones.get(bi).pos != null ? model.bones.get(bi).pos.clone() : new float[]{0,0,0});
            float[] r = ov != null ? ov.rot.clone() : (model.bones.get(bi).rot != null ? model.bones.get(bi).rot.clone() : new float[]{0,0,0});
            p[0] += bd.translation.x * weight; p[1] += bd.translation.y * weight; p[2] += bd.translation.z * weight;
            r[0] += bd.rotation[0] * weight; r[1] += bd.rotation[1] * weight; r[2] += bd.rotation[2] * weight;
            viewport.setBoneOverride(bi, new ModelViewport.BoneOverride(p, r));
        }
    }

    private int boneIndexByName(String name) {
        for (int i = 0; i < model.bones.size(); i++) if (model.bones.get(i).name.equals(name)) return i;
        return -1;
    }

    private int nearestKeyBelow(int bone, int frame) {
        int best = -1;
        for (var k : anim.boneKeys.keySet()) {
            if (k.bone == bone && k.frame <= frame && k.frame > best) best = k.frame;
        }
        return best;
    }

    private int nearestKeyAbove(int bone, int frame) {
        int best = Integer.MAX_VALUE;
        for (var k : anim.boneKeys.keySet()) {
            if (k.bone == bone && k.frame >= frame && k.frame < best) best = k.frame;
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private float[] lerp(float[] a, float[] b, int bone, int frame) {
        int below = nearestKeyBelow(bone, frame);
        int above = nearestKeyAbove(bone, frame);
        if (below < 0) return b.clone();
        if (above < 0 || above == below) return a.clone();
        float t = (float)(frame - below) / (above - below);
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) out[i] = a[i] + (b[i] - a[i]) * t;
        return out;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        if (model == null) {
            graphics.drawCenteredString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_no_model"),
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
        if (tab == Tab.BONE) {
            graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_bones"), panelX + 4, listY, 0xF3EFE0);
            for (int i = 0; i < Math.min(model.bones.size(), 16); i++) {
                int ry = listY + 14 + i * 13;
                boolean sel = i == selectedBone;
                if (sel) graphics.fillGradient(panelX + 2, ry, panelX + panelW - 2, ry + 12, 0xFF_5A5A5A, 0xFF_5A5A5A);
                String nm = model.bones.get(i).name;
                if (nm.length() > 26) nm = nm.substring(0, 26);
                graphics.drawString(font, nm, panelX + 6, ry + 2, sel ? 0xFFFFFF : 0xCCCCCC);
            }
        } else {
            graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_morphs"), panelX + 4, listY, 0xF3EFE0);
            for (int i = 0; i < Math.min(morphNames.size(), 16); i++) {
                int ry = listY + 14 + i * 13;
                boolean sel = i == selectedMorph;
                if (sel) graphics.fillGradient(panelX + 2, ry, panelX + panelW - 2, ry + 12, 0xFF_5A5A5A, 0xFF_5A5A5A);
                String nm = morphNames.get(i);
                if (nm.length() > 26) nm = nm.substring(0, 26);
                graphics.drawString(font, nm, panelX + 6, ry + 2, sel ? 0xFFFFFF : 0xCCCCCC);
            }
        }

        int ey = 40;
        if (tab == Tab.BONE) {
            graphics.drawString(font, "X", panelX, ey + 4, 0x88CCFF);
            graphics.drawString(font, "Y", panelX, ey + 24, 0x88CCFF);
            graphics.drawString(font, "Z", panelX, ey + 44, 0x88CCFF);
            graphics.drawString(font, "RX", panelX, ey + 74, 0xFFCC88);
            graphics.drawString(font, "RY", panelX, ey + 94, 0xFFCC88);
            graphics.drawString(font, "RZ", panelX, ey + 114, 0xFFCC88);
        } else {
            graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.editor_weight"), panelX, ey + 154, 0xAAAAAA);
        }

        drawTimeline(graphics, panelX, panelW);

        if (statusMsg != null && statusTimer > 0) {
            graphics.drawString(font, statusMsg, 12, height - 50, 0x88FF88);
            statusTimer--;
        }
        graphics.drawString(font, Component.translatable("gui.transferstation_whimsicalideas.anim_editor") + ": " + modelName
                + "  F:" + currentFrame + "/" + anim.frameCount, 76, 10, 0xF3EFE0);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void drawTimeline(GuiGraphics graphics, int panelX, int panelW) {
        int ty = viewport.getY() + viewport.getHeight() + 8;
        int tw = viewport.getWidth();
        int tx = viewport.getX();
        graphics.fillGradient(tx, ty, tx + tw, ty + 24, 0xFF_1A1A1A, 0xFF_1A1A1A);
        int frames = anim.frameCount;
        if (frames <= 0) return;
        for (int fr = 0; fr <= frames; fr++) {
            int fx = tx + (int)((float) fr / frames * tw);
            graphics.fillGradient(fx, ty, fx + 1, ty + 24, 0xFF_444444, 0xFF_444444);
        }
        if (tab == Tab.BONE) {
            for (var k : anim.boneKeys.keySet()) {
                if (k.bone != selectedBone) continue;
                int fx = tx + (int)((float) k.frame / frames * tw);
                graphics.fillGradient(fx - 2, ty + 2, fx + 2, ty + 10, 0xFFFFAA00, 0xFFFFAA00);
            }
        } else {
            for (var k : anim.morphKeys.keySet()) {
                if (selectedMorph < 0 || !k.morph.equals(morphNames.get(selectedMorph))) continue;
                int fx = tx + (int)((float) k.frame / frames * tw);
                graphics.fillGradient(fx - 2, ty + 2, fx + 2, ty + 10, 0xFF66CCFF, 0xFF66CCFF);
            }
        }
        int px = tx + (int)((float) currentFrame / frames * tw);
        graphics.fillGradient(px - 1, ty, px + 1, ty + 24, 0xFFFFFFFF, 0xFFFFFFFF);
    }

    private void drawBoneGizmos(GuiGraphics graphics) {
        for (int i = 0; i < model.bones.size(); i++) {
            var wp = viewport.boneWorldPos(i);
            if (wp == null) continue;
            int[] scr = viewport.projectToScreen(wp);
            if (scr == null) continue;
            int color = i == selectedBone ? 0xFFFFAA00 : 0xFF66CCFF;
            graphics.fillGradient(scr[0] - 2, scr[1] - 2, scr[0] + 2, scr[1] + 2, color, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (model == null) return super.mouseClicked(mouseX, mouseY, button);

        int ty = viewport.getY() + viewport.getHeight() + 8;
        if (mouseY >= ty && mouseY <= ty + 24 && mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()) {
            float frac = (float)((mouseX - viewport.getX()) / (double) viewport.getWidth());
            currentFrame = Math.max(0, Math.min(anim.frameCount, (int)(frac * anim.frameCount)));
            applyFrame();
            return true;
        }

        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            int hit = pickBone((int) mouseX, (int) mouseY);
            if (hit >= 0 && tab == Tab.BONE) { selectedBone = hit; refreshBoneFields(); return true; }
            if (button == 1) { panning = true; lastMouseX = mouseX; lastMouseY = mouseY; return true; }
            dragging = true; lastMouseX = mouseX; lastMouseY = mouseY; return true;
        }

        int panelX = viewport.getX() + viewport.getWidth() + 10;
        if (mouseX >= panelX && mouseX <= panelX + (width - panelX - 10)) {
            int listY = 30 + 14;
            if (mouseY >= listY && mouseY <= listY + 16 * 13) {
                int idx = (int)((mouseY - listY) / 13);
                if (tab == Tab.BONE) {
                    if (idx >= 0 && idx < model.bones.size()) { selectedBone = idx; refreshBoneFields(); }
                } else {
                    if (idx >= 0 && idx < morphNames.size()) selectedMorph = idx;
                }
                return true;
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
        dragging = false; panning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging) { viewport.orbit((float)(dx * 0.01), (float)(dy * 0.01)); return true; }
        if (panning) { viewport.pan((float) dx, (float) dy); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            viewport.zoom((float)(delta > 0 ? -0.1 : 0.1));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputConstants.KEY_ESCAPE) { onClose(); return true; }
        if (keyCode == InputConstants.KEY_LEFT) { currentFrame = Math.max(0, currentFrame - 1); applyFrame(); return true; }
        if (keyCode == InputConstants.KEY_RIGHT) { currentFrame = Math.min(anim.frameCount, currentFrame + 1); applyFrame(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() { Minecraft.getInstance().setScreen(parent); }

    public static class BoneKey {
        public final int bone;
        public final int frame;
        public BoneKey(int bone, int frame) { this.bone = bone; this.frame = frame; }
        @Override public boolean equals(Object o) { return o instanceof BoneKey k && k.bone == bone && k.frame == frame; }
        @Override public int hashCode() { return bone * 31 + frame; }
    }

    public static class MorphKey {
        public final String morph;
        public final int frame;
        public MorphKey(String morph, int frame) { this.morph = morph; this.frame = frame; }
        @Override public boolean equals(Object o) { return o instanceof MorphKey k && k.morph.equals(morph) && k.frame == frame; }
        @Override public int hashCode() { return morph.hashCode() * 31 + frame; }
    }

    public static class EditableAnimation {
        public int frameCount = 60;
        public int ticksPerFrame = 2;
        public boolean loop = true;
        public final Map<BoneKey, BoneKeyframe> boneKeys = new LinkedHashMap<>();
        public final Map<MorphKey, Float> morphKeys = new LinkedHashMap<>();

        public static class BoneKeyframe {
            public final float[] pos;
            public final float[] rot;
            public BoneKeyframe(float[] pos, float[] rot) { this.pos = pos; this.rot = rot; }
        }
    }

    private static Path resolvePackageDir(String name) {
        for (var pkg : GmodModelConfig.scanModelPackages()) {
            if (pkg.getName().equals(name)) return pkg.getPackageDir();
        }
        return null;
    }
}
