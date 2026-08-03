package transferstation.transferstation_whimsicalideas.client.debug;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import transferstation.transferstation_whimsicalideas.client.GmodModelConfig;
import transferstation.transferstation_whimsicalideas.client.animation.AnimationProcessor;
import transferstation.transferstation_whimsicalideas.client.editor.ModelViewport;
import transferstation.transferstation_whimsicalideas.client.model.JavaModelParserStrategy;
import transferstation.transferstation_whimsicalideas.client.model.ModelDiagnostics;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadDiagnostics;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadManager;
import transferstation.transferstation_whimsicalideas.client.model.ModelLoadProgress;
import transferstation.transferstation_whimsicalideas.client.model.ModelPackage;
import transferstation.transferstation_whimsicalideas.client.model.ModelParserProvider;
import transferstation.transferstation_whimsicalideas.client.model.ModelParserStrategy;
import transferstation.transferstation_whimsicalideas.client.model.NpcChatHandler;
import transferstation.transferstation_whimsicalideas.client.model.PhysicsBridge;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * In-game model debug screen.
 * <p>
 * Left: 3D preview of the target model in its rest pose (orbit/zoom/pan).
 * Right: YSM-style text panel with load state, system availability, load
 * diagnostics, file integrity, relevant variables, white-mesh detection,
 * body parts and the list of loaded model packages.
 */
@OnlyIn(Dist.CLIENT)
public class ModelDebugScreen extends Screen {

    private final Screen parent;

    private final ModelViewport viewport = new ModelViewport();

    private String modelName;
    private Path packageDir;
    private SourceModelData model;
    private ModelLoadDiagnostics diag;
    private boolean loading;
    private String loadError;

    /** Non-null when a specific parser strategy is forced for this screen. */
    private ModelParserStrategy forcedStrategy;

    /** Whether a full integrity scan (expensive) has been requested. */
    private boolean integrityScanned;
    private List<ModelDiagnostics.DiagnosticResult> integrityResults = List.of();

    /** Scroll offset (in lines) for the right-hand panel. */
    private int panelScroll = 0;

    private List<ModelPackage> packages = List.of();
    private int selectedPackageIndex = -1;

    // Viewport interaction state
    private boolean dragging = false;
    private boolean panning = false;
    private int lastMouseX, lastMouseY;

    private static final int PANEL_LINE_H = 10;

    public ModelDebugScreen(Screen parent) {
        super(Component.translatable("gui.transferstation_whimsicalideas.model_debug"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.clearWidgets();

        if (model == null && !loading) {
            resolveTargetModel();
        }

        int vpX = 10, vpY = 30;
        int vpW = Math.max(160, (int) (width * 0.58f));
        int vpH = height - 70;
        viewport.setRect(vpX, vpY, vpW, vpH);

        int by = height - 24;
        int btnW = 120;
        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.debug_reload"),
                btn -> reloadModel()
        ).pos(vpX, by).size(btnW, 18).build());

        addRenderableWidget(Button.builder(
                getStrategyToggleLabel(),
                btn -> toggleParserStrategy()
        ).pos(vpX + btnW + 6, by).size(btnW + 20, 18).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.back"),
                btn -> onClose()
        ).pos(10, 6).size(60, 18).build());
    }

    private void resolveTargetModel() {
        modelName = GmodModelConfig.getSelectedModelName();
        packages = GmodModelConfig.scanModelPackages();
        if (modelName == null || modelName.isEmpty()) {
            if (!packages.isEmpty()) {
                modelName = packages.get(0).getName();
            }
        }
        for (int i = 0; i < packages.size(); i++) {
            if (packages.get(i).getName().equals(modelName)) {
                selectedPackageIndex = i;
                break;
            }
        }
        if (modelName == null || modelName.isEmpty()) {
            loadError = "No models found";
            return;
        }
        packageDir = resolvePackageDir(modelName);
        if (packageDir == null) {
            loadError = "Package not found: " + modelName;
            return;
        }
        String cacheKey = packageDir.toAbsolutePath().toString();
        SourceModelData cached = ModelLoadManager.getCached(cacheKey);
        if (cached != null) {
            model = cached;
            diag = buildDiagnosticsFromModel(model);
            onModelReady();
        } else {
            loadAsync();
        }
    }

    private void loadAsync() {
        loading = true;
        loadError = null;
        if (packageDir == null) return;
        CompletableFuture<SourceModelData> future;
        if (forcedStrategy != null) {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return ModelLoadManager.loadModel(packageDir, forcedStrategy);
                } catch (Exception e) {
                    return null;
                }
            });
        } else {
            future = ModelLoadManager.loadModelAsync(packageDir);
        }
        future.whenComplete((data, t) -> {
            Minecraft.getInstance().execute(() -> {
                loading = false;
                if (data != null) {
                    model = data;
                    diag = buildDiagnosticsFromModel(model);
                    onModelReady();
                } else {
                    loadError = t != null ? t.getMessage() : "Load failed";
                }
            });
        });
    }

    private void reloadModel() {
        if (packageDir == null) return;
        String cacheKey = packageDir.toAbsolutePath().toString();
        ModelLoadManager.unloadModel(cacheKey);
        model = null;
        viewport.clearBoneMatrices();
        loadAsync();
    }

    private void toggleParserStrategy() {
        if (forcedStrategy != null) {
            forcedStrategy = null;
        } else {
            forcedStrategy = new JavaModelParserStrategy();
        }
        reloadModel();
    }

    private Component getStrategyToggleLabel() {
        String current = forcedStrategy != null
                ? forcedStrategy.getPlatformName()
                : ModelParserProvider.getActivePlatformName();
        String target = forcedStrategy != null
                ? ModelParserProvider.getActivePlatformName()
                : "Java";
        return Component.translatable("gui.transferstation_whimsicalideas.debug_switch_parser", current, target);
    }

    private void onModelReady() {
        if (model != null) {
            viewport.setModel(model);
            float[][] matrices = AnimationProcessor.getReferencePoseBoneTransforms(model);
            viewport.setBoneMatrices(matrices);
        }
    }

    private void selectPackage(int index) {
        if (index < 0 || index >= packages.size()) return;
        ModelPackage pkg = packages.get(index);
        modelName = pkg.getName();
        packageDir = pkg.getPackageDir();
        selectedPackageIndex = index;
        GmodModelConfig.setSelectedModelName(modelName);
        integrityScanned = false;
        integrityResults = List.of();
        panelScroll = 0;
        model = null;
        viewport.clearBoneMatrices();
        loadAsync();
    }

    // ==================== RENDERING ====================

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);

        graphics.drawCenteredString(font,
                Component.translatable("gui.transferstation_whimsicalideas.model_debug"),
                width / 2, 10, 0xF3EFE0);

        viewport.render(graphics.pose());

        int panelX = viewport.getX() + viewport.getWidth() + 10;
        int panelY = 30;
        int panelW = width - panelX - 10;
        int panelH = height - 56;

        List<String[]> lines = collectPanelLines();
        int visible = Math.max(1, panelH / PANEL_LINE_H);
        int maxScroll = Math.max(0, lines.size() - visible);
        if (panelScroll > maxScroll) panelScroll = maxScroll;
        if (panelScroll < 0) panelScroll = 0;

        int lineY = panelY;
        int endY = panelY + panelH;
        for (int i = panelScroll; i < lines.size() && lineY < endY; i++, lineY += PANEL_LINE_H) {
            String[] kv = lines.get(i);
            if (kv.length < 2) continue;
            graphics.drawString(font, kv[0], panelX, lineY, 0x8a8a8a);
            int valueColor = parseColor(kv[2]);
            graphics.drawString(font, kv[1], panelX + 120, lineY, valueColor);
        }

        // Scroll hint when content overflows
        if (lines.size() > visible) {
            graphics.drawString(font, Component.literal(panelScroll + "/" + maxScroll), panelX + panelW - 40, panelY + panelH - 10, 0x777777);
        }

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private int parseColor(String color) {
        try {
            return (int) Long.parseLong(color, 16);
        } catch (NumberFormatException e) {
            return 0xF3EFE0;
        }
    }

    // ==================== PANEL DATA ====================

    /**
     * Collect the panel lines as {name, value, color-hex} arrays.
     * Colors follow the YSM convention: floats blue, ints gold, booleans green/red.
     */
    private List<String[]> collectPanelLines() {
        List<String[]> lines = new ArrayList<>();

        // ---- 1. Load status (live) ----
        lines.add(section("1. LOAD STATUS"));
        ModelLoadProgress.Phase phase = ModelLoadProgress.getCurrentPhase();
        if (loading) {
            lines.add(row("Phase", phase.getDisplay(), "ffdd44"));
            if (ModelLoadProgress.isIndeterminate()) {
                lines.add(row("Progress", "indeterminate", "ffdd44"));
            } else {
                float p = ModelLoadProgress.getProgress();
                lines.add(row("Progress", p >= 0 ? String.format("%.0f%%", p * 100) : "-", "55ccff"));
            }
            lines.add(row("Item", ModelLoadProgress.getCurrentItem(), "dddddd"));
            lines.add(row("Elapsed", ModelLoadProgress.getElapsed(), "dddddd"));
        } else if (loadError != null) {
            lines.add(row("Status", "FAILED", "ff5555"));
            lines.add(row("Error", loadError, "ff5555"));
        } else if (model != null) {
            lines.add(row("Status", "LOADED", "55ff55"));
            lines.add(row("Elapsed", ModelLoadProgress.getElapsed(), "dddddd"));
        } else {
            lines.add(row("Status", "IDLE", "888888"));
        }

        // ---- 2. System availability ----
        lines.add(section("2. SYSTEM"));
        String cacheKey = packageDir != null ? packageDir.toAbsolutePath().toString() : null;
        boolean cached = cacheKey != null && ModelLoadManager.getCached(cacheKey) != null;
        lines.add(boolRow("Cache", cached));
        boolean diskCacheOk = GmodModelConfig.getCacheDir() != null
                && Files.isDirectory(GmodModelConfig.getCacheDir());
        lines.add(boolRow("Disk Cache", diskCacheOk));
        lines.add(boolRow("Physics", PhysicsBridge.isAvailable()));
        lines.add(boolRow("AI Chat", NpcChatHandler.isEnabled()));

        // ---- 3. Load diagnostics ----
        lines.add(section("3. LOAD DIAGNOSTICS"));
        if (diag != null) {
            lines.add(row("Parser", diag.parserStrategy, "dddddd"));
            lines.add(intRow("MDL Version", diag.mdlVersion));
            lines.add(intRow("Bones", diag.numBones));
            lines.add(intRow("BodyParts", diag.numBodyParts));
            lines.add(intRow("Meshes", diag.numMeshes));
            lines.add(intRow("Textures", diag.numTextures));
            lines.add(intRow("Vertices", diag.numVertices));
            lines.add(intRow("Triangles", diag.numTriangles));
            lines.add(row("Load Time", diag.loadTimeMs + " ms", "dddddd"));
            lines.add(boolRow("Success", diag.success));
        } else if (model != null) {
            lines.add(intRow("Bones", model.bones.size()));
            lines.add(intRow("Meshes", model.meshes.size()));
            lines.add(intRow("Triangles", model.totalTriangles()));
            lines.add(intRow("Vertices", model.totalVertices()));
        } else {
            lines.add(row("(none)", "", "888888"));
        }

        // ---- 4. File integrity ----
        lines.add(section("4. FILE INTEGRITY"));
        if (integrityScanned && !integrityResults.isEmpty()) {
            for (ModelDiagnostics.DiagnosticResult r : integrityResults) {
                String status = r.complete ? "COMPLETE" : "INCOMPLETE";
                lines.add(row(r.modelName, status, r.complete ? "55ff55" : "ff5555"));
                for (String w : r.warnings) {
                    lines.add(row("  warn", w, "ff5555"));
                }
            }
        } else if (integrityScanned) {
            lines.add(row("Scan", "no model groups", "888888"));
        } else {
            lines.add(row("Scan", "press [Reload] or [F5]-like scan", "888888"));
        }

        // ---- 5. Relevant variables ----
        lines.add(section("5. VARIABLES"));
        if (model != null) {
            lines.add(floatRow("Scale", model.modelScale));
            lines.add(floatRow("minX", model.minX));
            lines.add(floatRow("maxX", model.maxX));
            lines.add(floatRow("minY", model.minY));
            lines.add(floatRow("maxY", model.maxY));
            lines.add(floatRow("minZ", model.minZ));
            lines.add(floatRow("maxZ", model.maxZ));
            lines.add(intRow("Bones", model.bones.size()));
            lines.add(intRow("Meshes", model.meshes.size()));
            lines.add(intRow("Triangles", model.totalTriangles()));
            lines.add(intRow("Textures Registered", ModelLoadManager.getColorResolver().getStatistics().registeredTextures()));
            lines.add(row("Parser", forcedStrategy != null ? forcedStrategy.getPlatformName() : ModelParserProvider.getActivePlatformName(), "dddddd"));
        }

        // ---- 6. White-mesh detection ----
        lines.add(section("6. WHITE MESH"));
        if (model != null) {
            int whiteCount = 0;
            for (int i = 0; i < model.meshes.size(); i++) {
                SourceModelData.MeshData mesh = model.meshes.get(i);
                if (isWhiteMesh(mesh)) {
                    whiteCount++;
                    lines.add(row("mesh[" + i + "]", String.valueOf(mesh.vtfKey), "ff5555"));
                }
            }
            if (whiteCount == 0) {
                lines.add(row("OK", "all meshes textured", "55ff55"));
            } else {
                lines.add(row("BAD", whiteCount + " untextured mesh(es)", "ff5555"));
            }
        } else {
            lines.add(row("(none)", "", "888888"));
        }

        // ---- 7. Body parts ----
        lines.add(section("7. BODY PARTS"));
        if (model != null) {
            lines.add(intRow("Count", model.bodyParts.size()));
            for (SourceModelData.BodyPartInfo bp : model.bodyParts) {
                lines.add(row("  " + bp.name, bp.numModels + " models @base " + bp.baseIndex, "dddddd"));
            }
        } else {
            lines.add(row("(none)", "", "888888"));
        }

        // ---- 8. Loaded model list ----
        lines.add(section("8. MODELS (" + packages.size() + ")"));
        for (int i = 0; i < packages.size(); i++) {
            ModelPackage pkg = packages.get(i);
            boolean selected = i == selectedPackageIndex;
            lines.add(row((selected ? "> " : "  ") + pkg.getName(), "", selected ? "55ff55" : "dddddd"));
        }

        return lines;
    }

    private String[] section(String title) {
        return row(title, "", "55ccff");
    }

    private String[] row(String name, String value, String color) {
        return new String[]{name, value, color};
    }

    private String[] boolRow(String name, boolean value) {
        return row(name, String.valueOf(value), value ? "55ff55" : "ff5555");
    }

    private String[] intRow(String name, int value) {
        return row(name, String.valueOf(value), "ffdd44");
    }

    private String[] floatRow(String name, float value) {
        return row(name, String.format("%.3f", value), "55ccff");
    }

    /**
     * True when the mesh would render white: no texture bound, or its vtf key is
     * not registered in the color resolver. Shared with unit tests.
     */
    public static boolean isWhiteMesh(SourceModelData.MeshData mesh) {
        return mesh.texture == null
                || (mesh.vtfKey != null && !ModelLoadManager.getColorResolver().isRegistered(mesh.vtfKey));
    }

    // ==================== INTERACTION ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int panelX = viewport.getX() + viewport.getWidth() + 10;
        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            dragging = true;
            panning = button == 2;
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            return true;
        }
        // Click a model entry in the list
        if (mouseX >= panelX && mouseX <= panelX + 160) {
            int index = clickOnPackageEntry((int) mouseY);
            if (index >= 0) {
                selectPackage(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int clickOnPackageEntry(int mouseY) {
        // Find the "8. MODELS" section position in rendered lines
        List<String[]> lines = collectPanelLines();
        int panelY = 30;
        int lineY = panelY;
        boolean inModels = false;
        for (String[] kv : lines) {
            if (kv.length < 2) { lineY += PANEL_LINE_H; continue; }
            if (kv[0].startsWith("8. MODELS")) inModels = true;
            if (mouseY >= lineY && mouseY < lineY + PANEL_LINE_H && inModels && kv[0].startsWith("  ")) {
                // count which model index by matching against packages order
                for (int i = 0; i < packages.size(); i++) {
                    if (kv[0].contains(packages.get(i).getName())) return i;
                }
            }
            lineY += PANEL_LINE_H;
        }
        return -1;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            int dx = (int) (mouseX - lastMouseX);
            int dy = (int) (mouseY - lastMouseY);
            lastMouseX = (int) mouseX;
            lastMouseY = (int) mouseY;
            if (panning) {
                viewport.pan(dx, dy);
            } else {
                viewport.orbit(dx * 0.01f, dy * 0.01f);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        dragging = false;
        panning = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int panelX = viewport.getX() + viewport.getWidth() + 10;
        if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
            viewport.zoom((float) (-delta * 0.1));
            return true;
        }
        if (mouseX >= panelX) {
            panelScroll -= (int) delta;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256) { // ESC
            onClose();
            return true;
        }
        if (keyCode == 264) { // Down
            panelScroll++;
            return true;
        }
        if (keyCode == 265) { // Up
            panelScroll--;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== HELPERS ====================

    private Path resolvePackageDir(String name) {
        for (ModelPackage pkg : GmodModelConfig.scanModelPackages()) {
            if (pkg.getName().equals(name)) return pkg.getPackageDir();
        }
        return null;
    }

    private ModelLoadDiagnostics buildDiagnosticsFromModel(SourceModelData data) {
        ModelLoadDiagnostics.Builder b = new ModelLoadDiagnostics.Builder();
        b.modelName(data.name);
        b.numBones(data.bones.size());
        b.numBodyParts(data.bodyParts.size());
        b.numMeshes(data.meshes.size());
        b.numVertices(data.totalVertices());
        b.numTriangles(data.totalTriangles());
        b.parserStrategy(forcedStrategy != null ? forcedStrategy.getPlatformName() : ModelParserProvider.getActivePlatformName());
        b.loadTimeMs(0);
        b.success(true);
        List<String> bpNames = new ArrayList<>();
        for (SourceModelData.BodyPartInfo bp : data.bodyParts) {
            bpNames.add(bp.name);
        }
        b.bodyPartNames(bpNames);
        return b.build();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
