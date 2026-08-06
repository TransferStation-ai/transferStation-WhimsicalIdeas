package transferstation.transferstation_whimsicalideas.client.debug;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
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

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@OnlyIn(Dist.CLIENT)
public class ModelDebugScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PANEL_LINE_H = 10;
    private static final String[] SECTION_PREFIXES = {
        "1. LOAD STATUS", "2. SYSTEM", "3. LOAD DIAGNOSTICS", "4. FILE INTEGRITY",
        "5. VARIABLES", "6. WHITE MESH", "7. BODY PARTS", "8. MODELS",
        "9. VALIDATION", "10. LIVE MONITORING", "11. COMPARISON"
    };

    private final Screen parent;
    private final ModelViewport viewport = new ModelViewport();

    // Comparison viewport (shown when comparison mode is active)
    private final ModelViewport comparisonViewport = new ModelViewport();
    private boolean comparisonMode = false;
    private String comparisonModelName;
    private Path comparisonPackageDir;
    private SourceModelData comparisonModel;
    private boolean comparisonLoading;

    private String modelName;
    private Path packageDir;
    private SourceModelData model;
    private ModelLoadDiagnostics diag;
    private boolean loading;
    private String loadError;

    private ModelParserStrategy forcedStrategy;
    private boolean integrityScanned;
    private List<ModelDiagnostics.DiagnosticResult> integrityResults = List.of();

    private int panelScroll = 0;
    private List<ModelPackage> packages = List.of();
    private int selectedPackageIndex = -1;

    // Viewport interaction state
    private boolean dragging = false;
    private boolean panning = false;
    private int lastMouseX, lastMouseY;

    // Search/filter
    private EditBox searchBox;
    private String filterText = "";

    // Collapsible sections
    private final Map<String, Boolean> sectionCollapsed = new LinkedHashMap<>();

    // Live monitoring
    private long lastFpsTime;
    private int fpsCounter;
    private int displayFps;
    private long lastMemoryCheck;
    private long usedMemoryMB;
    private long totalMemoryMB;
    private long gcCount;
    private long gcTimeMs;

    // Export status
    private String exportStatus = "";
    private long exportStatusTime;

    // Validation warnings
    private List<String> validationWarnings = List.of();

    public ModelDebugScreen(Screen parent) {
        super(Component.translatable("gui.transferstation_whimsicalideas.model_debug"));
        this.parent = parent;
        for (String s : SECTION_PREFIXES) {
            sectionCollapsed.put(s, false);
        }
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

        if (comparisonMode) {
            int halfW = (vpW - 6) / 2;
            viewport.setRect(vpX, vpY, halfW, vpH);
            comparisonViewport.setRect(vpX + halfW + 6, vpY, halfW, vpH);
        } else {
            viewport.setRect(vpX, vpY, vpW, vpH);
            comparisonViewport.setRect(0, 0, 0, 0);
        }

        int by = height - 24;
        int btnW = 100;
        int bx = vpX;

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.debug_reload"),
                btn -> reloadModel()
        ).pos(bx, by).size(btnW, 18).build());

        addRenderableWidget(Button.builder(
                getStrategyToggleLabel(),
                btn -> toggleParserStrategy()
        ).pos(bx + btnW + 4, by).size(btnW + 20, 18).build());

        addRenderableWidget(Button.builder(
                Component.literal("Export"),
                btn -> exportToJson()
        ).pos(bx + btnW * 2 + 8, by).size(btnW - 20, 18).build());

        addRenderableWidget(Button.builder(
                Component.literal(comparisonMode ? "Exit Compare" : "Compare"),
                btn -> toggleComparisonMode()
        ).pos(bx + btnW * 3 + 12, by).size(btnW - 20, 18).build());

        addRenderableWidget(Button.builder(
                Component.translatable("gui.transferstation_whimsicalideas.back"),
                btn -> onClose()
        ).pos(10, 6).size(60, 18).build());

        // Search box
        searchBox = new EditBox(font, vpX, by - 22, vpW, 16,
                Component.literal("Filter..."));
        searchBox.setResponder(s -> {
            filterText = s.toLowerCase();
            panelScroll = 0;
        });
        addRenderableWidget(searchBox);
    }

    // ==================== MODEL LOADING ====================

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
        runValidationChecks();
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
                    runValidationChecks();
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
        validationWarnings = List.of();
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
        validationWarnings = List.of();
        loadAsync();
    }

    // ==================== COMPARISON MODE ====================

    private void toggleComparisonMode() {
        comparisonMode = !comparisonMode;
        if (!comparisonMode) {
            comparisonModel = null;
            comparisonModelName = null;
            comparisonPackageDir = null;
            comparisonLoading = false;
        } else {
            // Pick next model in list as comparison target
            int nextIdx = (selectedPackageIndex + 1) % Math.max(1, packages.size());
            if (nextIdx != selectedPackageIndex && !packages.isEmpty()) {
                loadComparisonModel(packages.get(nextIdx));
            }
        }
        init();
    }

    private void loadComparisonModel(ModelPackage pkg) {
        comparisonModelName = pkg.getName();
        comparisonPackageDir = pkg.getPackageDir();
        comparisonLoading = true;
        comparisonModel = null;
        comparisonViewport.clearBoneMatrices();

        String cacheKey = comparisonPackageDir.toAbsolutePath().toString();
        SourceModelData cached = ModelLoadManager.getCached(cacheKey);
        if (cached != null) {
            comparisonModel = cached;
            setupComparisonViewport();
            comparisonLoading = false;
        } else {
            ModelLoadManager.loadModelAsync(comparisonPackageDir).whenComplete((data, t) -> {
                Minecraft.getInstance().execute(() -> {
                    comparisonLoading = false;
                    if (data != null) {
                        comparisonModel = data;
                        setupComparisonViewport();
                    }
                });
            });
        }
    }

    private void setupComparisonViewport() {
        if (comparisonModel != null) {
            comparisonViewport.setModel(comparisonModel);
            float[][] matrices = AnimationProcessor.getReferencePoseBoneTransforms(comparisonModel);
            comparisonViewport.setBoneMatrices(matrices);
        }
    }

    // ==================== VALIDATION ====================

    private void runValidationChecks() {
        List<String> warnings = new ArrayList<>();
        if (model == null) {
            validationWarnings = warnings;
            return;
        }

        // Check scale
        if (model.modelScale <= 0) {
            warnings.add("SCALE: modelScale <= 0 (" + model.modelScale + ") - model may be invisible");
        } else if (model.modelScale > 10) {
            warnings.add("SCALE: modelScale > 10 (" + model.modelScale + ") - unusually large");
        }

        // Check bounds
        float spanX = model.maxX - model.minX;
        float spanY = model.maxY - model.minY;
        float spanZ = model.maxZ - model.minZ;
        if (spanX <= 0 && spanY <= 0 && spanZ <= 0) {
            warnings.add("BOUNDS: all spans zero - degenerate model");
        }
        if (spanY > 500) {
            warnings.add("BOUNDS: Y span > 500 (" + String.format("%.1f", spanY) + ") - very tall");
        }

        // Check bones
        if (model.bones.isEmpty()) {
            warnings.add("BONES: no bones defined");
        } else {
            for (int i = 0; i < model.bones.size(); i++) {
                SourceModelData.BoneInfo bone = model.bones.get(i);
                if (bone.parent() >= i && bone.parent() >= 0) {
                    warnings.add("BONE[" + i + "]: parent (" + bone.parent() + ") >= self - circular hierarchy");
                }
            }
        }

        // Check meshes
        if (model.meshes.isEmpty()) {
            warnings.add("MESHES: no meshes found");
        } else {
            int emptyMeshes = 0;
            int whiteMeshes = 0;
            for (int i = 0; i < model.meshes.size(); i++) {
                SourceModelData.MeshData mesh = model.meshes.get(i);
                if (mesh.indices.length < 3) {
                    emptyMeshes++;
                }
                if (isWhiteMesh(mesh)) {
                    whiteMeshes++;
                }
            }
            if (emptyMeshes > 0) {
                warnings.add("MESHES: " + emptyMeshes + " mesh(es) with < 3 indices (empty)");
            }
            if (whiteMeshes > 0) {
                warnings.add("MESHES: " + whiteMeshes + " untextured mesh(es) - will render white");
            }
        }

        // Check body parts
        if (model.bodyParts.isEmpty()) {
            warnings.add("BODY PARTS: none defined");
        }

        // Check texture registration
        int registered = ModelLoadManager.getColorResolver().getStatistics().registeredTextures();
        if (model.meshes.size() > 0 && registered == 0) {
            warnings.add("TEXTURES: 0 registered in color resolver");
        }

        // Check for duplicate bone names
        if (model.bones.size() > 1) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (SourceModelData.BoneInfo bone : model.bones) {
                if (!seen.add(bone.name())) {
                    warnings.add("BONES: duplicate name '" + bone.name() + "'");
                    break;
                }
            }
        }

        validationWarnings = warnings;
    }

    // ==================== EXPORT ====================

    private void exportToJson() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        data.put("modelName", modelName != null ? modelName : "unknown");
        data.put("parserStrategy", forcedStrategy != null
                ? forcedStrategy.getPlatformName()
                : ModelParserProvider.getActivePlatformName());

        // System info
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("cacheKey", packageDir != null ? packageDir.toAbsolutePath().toString() : null);
        system.put("physics", PhysicsBridge.isAvailable());
        system.put("aiChat", NpcChatHandler.isEnabled());
        data.put("system", system);

        // Diagnostics
        if (diag != null) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("parser", diag.parserStrategy);
            d.put("mdlVersion", diag.mdlVersion);
            d.put("bones", diag.numBones);
            d.put("bodyParts", diag.numBodyParts);
            d.put("meshes", diag.numMeshes);
            d.put("textures", diag.numTextures);
            d.put("vertices", diag.numVertices);
            d.put("triangles", diag.numTriangles);
            d.put("loadTimeMs", diag.loadTimeMs);
            d.put("success", diag.success);
            d.put("warnings", diag.warnings);
            data.put("diagnostics", d);
        }

        // Model data
        if (model != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("scale", model.modelScale);
            m.put("bounds", Map.of(
                    "minX", model.minX, "maxX", model.maxX,
                    "minY", model.minY, "maxY", model.maxY,
                    "minZ", model.minZ, "maxZ", model.maxZ));
            m.put("boneCount", model.bones.size());
            m.put("meshCount", model.meshes.size());
            m.put("triangleCount", model.totalTriangles());
            m.put("vertexCount", model.totalVertices());
            m.put("bodyPartCount", model.bodyParts.size());

            List<Map<String, Object>> meshList = new ArrayList<>();
            for (int i = 0; i < model.meshes.size(); i++) {
                SourceModelData.MeshData mesh = model.meshes.get(i);
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("index", i);
                md.put("vertices", mesh.vertexCount());
                md.put("triangles", mesh.triangleCount());
                md.put("vtfKey", mesh.vtfKey);
                md.put("translucent", mesh.translucent);
                md.put("alphaTest", mesh.alphaTest);
                md.put("selfIllum", mesh.selfIllum);
                md.put("whiteMesh", isWhiteMesh(mesh));
                meshList.add(md);
            }
            m.put("meshes", meshList);

            List<Map<String, Object>> boneList = new ArrayList<>();
            for (int i = 0; i < model.bones.size(); i++) {
                SourceModelData.BoneInfo bone = model.bones.get(i);
                Map<String, Object> bd = new LinkedHashMap<>();
                bd.put("index", i);
                bd.put("name", bone.name());
                bd.put("parent", bone.parent());
                bd.put("pos", bone.pos());
                boneList.add(bd);
            }
            m.put("bones", boneList);

            List<Map<String, Object>> bpList = new ArrayList<>();
            for (SourceModelData.BodyPartInfo bp : model.bodyParts) {
                Map<String, Object> bpd = new LinkedHashMap<>();
                bpd.put("name", bp.name);
                bpd.put("numModels", bp.numModels);
                bpd.put("baseIndex", bp.baseIndex);
                bpd.put("modelNames", bp.modelNames);
                bpList.add(bpd);
            }
            m.put("bodyParts", bpList);
            data.put("model", m);
        }

        // Integrity
        if (!integrityResults.isEmpty()) {
            List<Map<String, Object>> ir = new ArrayList<>();
            for (ModelDiagnostics.DiagnosticResult r : integrityResults) {
                Map<String, Object> ri = new LinkedHashMap<>();
                ri.put("modelName", r.modelName);
                ri.put("complete", r.complete);
                ri.put("hasPhy", r.hasPhy);
                ri.put("warnings", r.warnings);
                ri.put("checksums", r.checksums);
                ir.add(ri);
            }
            data.put("integrity", ir);
        }

        // Validation
        data.put("validationWarnings", validationWarnings);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(data);

        CompletableFuture.runAsync(() -> {
            try {
                Path exportDir = GmodModelConfig.getCacheDir().resolve("debug_exports");
                Files.createDirectories(exportDir);
                String safeName = (modelName != null ? modelName : "unknown")
                        .replaceAll("[^a-zA-Z0-9_\\-]", "_");
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Path file = exportDir.resolve(safeName + "_" + timestamp + ".json");
                Files.writeString(file, json);
                Minecraft.getInstance().execute(() -> {
                    exportStatus = "Exported to: " + file.getFileName();
                    exportStatusTime = System.currentTimeMillis();
                    LOGGER.info("Model debug exported to {}", file);
                });
            } catch (IOException e) {
                Minecraft.getInstance().execute(() -> {
                    exportStatus = "Export failed: " + e.getMessage();
                    exportStatusTime = System.currentTimeMillis();
                });
                LOGGER.error("Failed to export model debug", e);
            }
        });
    }

    // ==================== RENDERING ====================

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);

        String title = comparisonMode
                ? "Model Debug - Compare: " + (comparisonModelName != null ? comparisonModelName : "none")
                : "Model Debug";
        graphics.drawCenteredString(font, title, width / 2, 10, 0xF3EFE0);

        // Model name label
        String nameLabel = modelName != null ? modelName : "(no model)";
        graphics.drawString(font, nameLabel, viewport.getX(), 22, 0x888888);

        if (comparisonMode && comparisonModelName != null) {
            graphics.drawString(font, comparisonModelName,
                    comparisonViewport.getX(), 22, 0x888888);
        }

        viewport.render(graphics.pose());
        if (comparisonMode && comparisonModel != null) {
            comparisonViewport.render(graphics.pose());
        }

        // Panel layout depends on comparison mode
        int panelX, panelY, panelW, panelH;
        if (comparisonMode) {
            panelX = 10;
            panelY = 30;
            panelW = width - 20;
            panelH = height - 56;
        } else {
            panelX = viewport.getX() + viewport.getWidth() + 10;
            panelY = 30;
            panelW = width - panelX - 10;
            panelH = height - 56;
        }

        List<String[]> lines = collectPanelLines();
        int visible = Math.max(1, panelH / PANEL_LINE_H);
        int maxScroll = Math.max(0, lines.size() - visible);
        if (panelScroll > maxScroll) panelScroll = maxScroll;
        if (panelScroll < 0) panelScroll = 0;

        int lineY = panelY;
        int endY = panelY + panelH;
        for (int i = panelScroll; i < lines.size() && lineY < endY; i++, lineY += PANEL_LINE_H) {
            String[] kv = lines.get(i);
            if (kv.length < 3) continue;

            int valueColor;
            try {
                valueColor = (int) Long.parseLong(kv[2], 16);
            } catch (NumberFormatException e) {
                valueColor = 0xF3EFE0;
            }

            // Section headers
            boolean isSection = kv[0].endsWith(".") || kv[0].startsWith(">");
            if (kv[2].equals("section")) {
                isSection = true;
                valueColor = 0x55CCFF;
            }

            if (isSection) {
                graphics.drawString(font, kv[0], panelX, lineY, valueColor);
            } else if (kv[0].startsWith("  ")) {
                graphics.drawString(font, kv[0], panelX, lineY, valueColor);
            } else {
                graphics.drawString(font, kv[0], panelX, lineY, 0x8A8A8A);
                graphics.drawString(font, kv[1], panelX + 130, lineY, valueColor);
            }
        }

        // Scroll indicator
        if (lines.size() > visible) {
            int scrollY = panelY;
            int scrollH = panelH;
            int thumbH = Math.max(10, (int) ((float) visible / lines.size() * scrollH));
            int thumbY = scrollY + (int) ((float) panelScroll / Math.max(1, maxScroll) * (scrollH - thumbH));
            graphics.fill(panelX + panelW - 4, scrollY, panelX + panelW - 1, scrollY + scrollH, 0x333333);
            graphics.fill(panelX + panelW - 4, thumbY, panelX + panelW - 1, thumbY + thumbH, 0x888888);
            graphics.drawString(font, panelScroll + "/" + maxScroll,
                    panelX + panelW - 40, panelY + panelH - 10, 0x777777);
        }

        // Export status
        if (!exportStatus.isEmpty() && System.currentTimeMillis() - exportStatusTime < 4000) {
            graphics.drawString(font, exportStatus, 10, height - 40, 0x55FF55);
        }

        // Search hint
        graphics.drawString(font, "[F] Filter:", panelX, panelY - 12, 0x666666);

        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    // ==================== PANEL DATA ====================

    private List<String[]> collectPanelLines() {
        List<String[]> lines = new ArrayList<>();

        // 1. LOAD STATUS
        lines.add(section("1. LOAD STATUS", "1"));
        if (!isSectionCollapsed("1. LOAD STATUS")) {
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
        }

        // 2. SYSTEM
        lines.add(section("2. SYSTEM", "2"));
        if (!isSectionCollapsed("2. SYSTEM")) {
            String cacheKey = packageDir != null ? packageDir.toAbsolutePath().toString() : null;
            boolean cached = cacheKey != null && ModelLoadManager.getCached(cacheKey) != null;
            lines.add(boolRow("Cache", cached));
            boolean diskCacheOk = GmodModelConfig.getCacheDir() != null
                    && Files.isDirectory(GmodModelConfig.getCacheDir());
            lines.add(boolRow("Disk Cache", diskCacheOk));
            lines.add(boolRow("Physics", PhysicsBridge.isAvailable()));
            lines.add(boolRow("AI Chat", NpcChatHandler.isEnabled()));
            lines.add(boolRow("Comparison", comparisonMode));
        }

        // 3. LOAD DIAGNOSTICS
        lines.add(section("3. LOAD DIAGNOSTICS", "3"));
        if (!isSectionCollapsed("3. LOAD DIAGNOSTICS")) {
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
                if (diag.numSequences > 0) lines.add(intRow("Sequences", diag.numSequences));
                if (diag.numAnimations > 0) lines.add(intRow("Animations", diag.numAnimations));
                if (diag.numIncludeModels > 0) lines.add(intRow("IncludeModels", diag.numIncludeModels));
                if (diag.checksumMdl != -1) lines.add(row("MDL Checksum", String.format("0x%08X", diag.checksumMdl), "888888"));
                if (diag.checksumVvd != -1) lines.add(row("VVD Checksum", String.format("0x%08X", diag.checksumVvd), "888888"));
                if (diag.checksumVtx != -1) lines.add(row("VTX Checksum", String.format("0x%08X", diag.checksumVtx), "888888"));
            } else if (model != null) {
                lines.add(intRow("Bones", model.bones.size()));
                lines.add(intRow("Meshes", model.meshes.size()));
                lines.add(intRow("Triangles", model.totalTriangles()));
                lines.add(intRow("Vertices", model.totalVertices()));
            } else {
                lines.add(row("(none)", "", "888888"));
            }
        }

        // 4. FILE INTEGRITY
        lines.add(section("4. FILE INTEGRITY", "4"));
        if (!isSectionCollapsed("4. FILE INTEGRITY")) {
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
                lines.add(row("Scan", "press [Reload] to scan", "888888"));
            }
        }

        // 5. VARIABLES
        lines.add(section("5. VARIABLES", "5"));
        if (!isSectionCollapsed("5. VARIABLES")) {
            if (model != null) {
                lines.add(floatRow("Scale", model.modelScale));
                lines.add(floatRow("minX", model.minX));
                lines.add(floatRow("maxX", model.maxX));
                lines.add(floatRow("minY", model.minY));
                lines.add(floatRow("maxY", model.maxY));
                lines.add(floatRow("minZ", model.minZ));
                lines.add(floatRow("maxZ", model.maxZ));
                float spanX = model.maxX - model.minX;
                float spanY = model.maxY - model.minY;
                float spanZ = model.maxZ - model.minZ;
                lines.add(row("SpanX", String.format("%.3f", spanX), "55ccff"));
                lines.add(row("SpanY", String.format("%.3f", spanY), "55ccff"));
                lines.add(row("SpanZ", String.format("%.3f", spanZ), "55ccff"));
                lines.add(intRow("Bones", model.bones.size()));
                lines.add(intRow("Meshes", model.meshes.size()));
                lines.add(intRow("Triangles", model.totalTriangles()));
                lines.add(intRow("Vertices", model.totalVertices()));
                lines.add(intRow("Tex Registered", ModelLoadManager.getColorResolver().getStatistics().registeredTextures()));
                lines.add(row("Parser", forcedStrategy != null ? forcedStrategy.getPlatformName() : ModelParserProvider.getActivePlatformName(), "dddddd"));
                if (model.numSkinRef > 0) lines.add(intRow("SkinRef", model.numSkinRef));
                if (model.numSkinFamilies > 0) lines.add(intRow("SkinFamilies", model.numSkinFamilies));
                lines.add(row("SurfaceProp", model.surfaceProp != null ? model.surfaceProp : "(none)", "dddddd"));
            }
        }

        // 6. WHITE MESH
        lines.add(section("6. WHITE MESH", "6"));
        if (!isSectionCollapsed("6. WHITE MESH")) {
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
        }

        // 7. BODY PARTS
        lines.add(section("7. BODY PARTS", "7"));
        if (!isSectionCollapsed("7. BODY PARTS")) {
            if (model != null) {
                lines.add(intRow("Count", model.bodyParts.size()));
                for (SourceModelData.BodyPartInfo bp : model.bodyParts) {
                    lines.add(row("  " + bp.name, bp.numModels + " models @base " + bp.baseIndex, "dddddd"));
                }
            } else {
                lines.add(row("(none)", "", "888888"));
            }
        }

        // 8. MODELS
        lines.add(section("8. MODELS (" + packages.size() + ")", "8"));
        if (!isSectionCollapsed("8. MODELS (" + packages.size() + ")")) {
            for (int i = 0; i < packages.size(); i++) {
                ModelPackage pkg = packages.get(i);
                boolean selected = i == selectedPackageIndex;
                boolean isCompare = comparisonMode && comparisonModelName != null
                        && pkg.getName().equals(comparisonModelName);
                String prefix = selected ? "> " : (isCompare ? "= " : "  ");
                String color = selected ? "55ff55" : (isCompare ? "ffaa44" : "dddddd");
                lines.add(row(prefix + pkg.getName(), "", color));
            }
        }

        // 9. VALIDATION
        lines.add(section("9. VALIDATION", "9"));
        if (!isSectionCollapsed("9. VALIDATION")) {
            if (validationWarnings.isEmpty()) {
                lines.add(row("Status", "ALL CHECKS PASSED", "55ff55"));
            } else {
                lines.add(row("Warnings", String.valueOf(validationWarnings.size()), "ff8844"));
                for (String w : validationWarnings) {
                    lines.add(row("  !", w, "ff8844"));
                }
            }
        }

        // 10. LIVE MONITORING
        lines.add(section("10. LIVE MONITORING", "10"));
        if (!isSectionCollapsed("10. LIVE MONITORING")) {
            updateLiveMetrics();
            lines.add(row("FPS", String.valueOf(displayFps), displayFps >= 55 ? "55ff55" : (displayFps >= 30 ? "ffdd44" : "ff5555")));
            lines.add(row("Used Memory", usedMemoryMB + " MB", "55ccff"));
            lines.add(row("Total Memory", totalMemoryMB + " MB", "55ccff"));
            lines.add(row("GC Count", String.valueOf(gcCount), "ffdd44"));
            lines.add(row("GC Time", gcTimeMs + " ms", "ffdd44"));
            long heapPct = totalMemoryMB > 0 ? (usedMemoryMB * 100 / totalMemoryMB) : 0;
            String heapColor = heapPct > 85 ? "ff5555" : (heapPct > 65 ? "ffdd44" : "55ff55");
            lines.add(row("Heap Usage", heapPct + "%", heapColor));
            Runtime rt = Runtime.getRuntime();
            lines.add(row("Available CPUs", String.valueOf(rt.availableProcessors()), "888888"));
        }

        // 11. COMPARISON
        if (comparisonMode) {
            lines.add(section("11. COMPARISON", "11"));
            if (!isSectionCollapsed("11. COMPARISON")) {
                lines.add(row("Model A", modelName != null ? modelName : "-", "55ff55"));
                lines.add(row("Model B", comparisonModelName != null ? comparisonModelName : "-", "ffaa44"));
                if (model != null && comparisonModel != null) {
                    lines.add(row("", "", "888888"));
                    lines.add(row("--- Differences ---", "", "55ccff"));
                    diffRow(lines, "Bones", model.bones.size(), comparisonModel.bones.size());
                    diffRow(lines, "Meshes", model.meshes.size(), comparisonModel.meshes.size());
                    diffRow(lines, "Triangles", model.totalTriangles(), comparisonModel.totalTriangles());
                    diffRow(lines, "Vertices", model.totalVertices(), comparisonModel.totalVertices());
                    diffRow(lines, "BodyParts", model.bodyParts.size(), comparisonModel.bodyParts.size());
                    diffFloatRow(lines, "Scale", model.modelScale, comparisonModel.modelScale);
                    diffFloatRow(lines, "SpanX", model.maxX - model.minX, comparisonModel.maxX - comparisonModel.minX);
                    diffFloatRow(lines, "SpanY", model.maxY - model.minY, comparisonModel.maxY - comparisonModel.minY);
                    diffFloatRow(lines, "SpanZ", model.maxZ - model.minZ, comparisonModel.maxZ - comparisonModel.minZ);
                } else if (comparisonLoading) {
                    lines.add(row("Status", "Loading comparison model...", "ffdd44"));
                } else {
                    lines.add(row("Status", "Model B not loaded", "888888"));
                }
            }
        }

        // Apply filter
        if (!filterText.isEmpty()) {
            lines = filterLines(lines);
        }

        return lines;
    }

    private List<String[]> filterLines(List<String[]> lines) {
        List<String[]> filtered = new ArrayList<>();
        boolean inMatchingSection = false;
        for (String[] kv : lines) {
            String text = (kv[0] + " " + kv[1]).toLowerCase();
            boolean isSection = kv[2].equals("section") || kv[0].endsWith(".");
            if (isSection) {
                inMatchingSection = text.contains(filterText);
                if (inMatchingSection) {
                    filtered.add(kv);
                }
            } else if (inMatchingSection || text.contains(filterText)) {
                filtered.add(kv);
            }
        }
        return filtered;
    }

    // ==================== LIVE METRICS ====================

    private void updateLiveMetrics() {
        long now = System.currentTimeMillis();

        // FPS counter
        fpsCounter++;
        if (now - lastFpsTime >= 1000) {
            displayFps = fpsCounter;
            fpsCounter = 0;
            lastFpsTime = now;
        }

        // Memory (check every 500ms)
        if (now - lastMemoryCheck >= 500) {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memBean.getHeapMemoryUsage();
            usedMemoryMB = heap.getUsed() / (1024 * 1024);
            totalMemoryMB = heap.getMax() / (1024 * 1024);

            long totalGcCount = 0;
            long totalGcTime = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                totalGcCount += gc.getCollectionCount();
                totalGcTime += gc.getCollectionTime();
            }
            gcCount = totalGcCount;
            gcTimeMs = totalGcTime;

            lastMemoryCheck = now;
        }
    }

    // ==================== ROW HELPERS ====================

    private String[] section(String title, String id) {
        boolean collapsed = isSectionCollapsed(title);
        String prefix = collapsed ? "[+] " : "[-] ";
        return new String[]{prefix + title, "", "section"};
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

    private void diffRow(List<String[]> lines, String name, int a, int b) {
        String color = a == b ? "55ff55" : "ff8844";
        String diff = a == b ? "==" : (a > b ? "+" : "") + (a - b);
        lines.add(row(name, a + " vs " + b + " (" + diff + ")", color));
    }

    private void diffFloatRow(List<String[]> lines, String name, float a, float b) {
        String color = Float.compare(a, b) == 0 ? "55ff55" : "ff8844";
        String diff = Float.compare(a, b) == 0 ? "==" : String.format("%+.3f", b - a);
        lines.add(row(name, String.format("%.3f", a) + " vs " + String.format("%.3f", b) + " (" + diff + ")", color));
    }

    private boolean isSectionCollapsed(String sectionKey) {
        return Boolean.TRUE.equals(sectionCollapsed.get(sectionKey));
    }

    // ==================== CLICK HANDLING (sections + models) ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int panelX, panelY;
        if (comparisonMode) {
            panelX = 10;
            panelY = 30;
        } else {
            panelX = viewport.getX() + viewport.getWidth() + 10;
            panelY = 30;
        }
        int panelW = width - panelX - 10;
        int panelH = height - 56;

        // Viewport click (only single-viewport mode)
        if (!comparisonMode) {
            if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                    && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
                dragging = true;
                panning = button == 2;
                lastMouseX = (int) mouseX;
                lastMouseY = (int) mouseY;
                return true;
            }
        } else {
            // Comparison viewport clicks
            if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                    && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
                dragging = true;
                panning = button == 2;
                lastMouseX = (int) mouseX;
                lastMouseY = (int) mouseY;
                return true;
            }
            if (comparisonModel != null
                    && mouseX >= comparisonViewport.getX() && mouseX <= comparisonViewport.getX() + comparisonViewport.getWidth()
                    && mouseY >= comparisonViewport.getY() && mouseY <= comparisonViewport.getY() + comparisonViewport.getHeight()) {
                dragging = true;
                panning = button == 2;
                lastMouseX = (int) mouseX;
                lastMouseY = (int) mouseY;
                return true;
            }
        }

        // Panel clicks (section collapse, model selection)
        if (mouseX >= panelX && mouseX <= panelX + panelW) {
            List<String[]> lines = collectPanelLines();
            int lineY = panelY;
            for (int i = panelScroll; i < lines.size() && lineY < panelY + panelH; i++, lineY += PANEL_LINE_H) {
                if (mouseY >= lineY && mouseY < lineY + PANEL_LINE_H) {
                    String[] kv = lines.get(i);
                    if (kv[2].equals("section")) {
                        String sectionTitle = kv[0].replaceAll("^\\[.\\] ", "");
                        boolean current = isSectionCollapsed(sectionTitle);
                        sectionCollapsed.put(sectionTitle, !current);
                        return true;
                    }
                    // Model selection
                    if (kv[0].startsWith("  ") || kv[0].startsWith("> ") || kv[0].startsWith("= ")) {
                        int index = clickOnPackageEntry(kv);
                        if (index >= 0) {
                            selectPackage(index);
                            return true;
                        }
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private int clickOnPackageEntry(String[] kv) {
        String name = kv[0].replaceAll("^[><= ] ", "");
        for (int i = 0; i < packages.size(); i++) {
            if (packages.get(i).getName().equals(name)) return i;
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
                if (comparisonMode) comparisonViewport.pan(dx, dy);
            } else {
                viewport.orbit(dx * 0.01f, dy * 0.01f);
                if (comparisonMode) comparisonViewport.orbit(dx * 0.01f, dy * 0.01f);
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
        int panelX;
        if (comparisonMode) {
            panelX = 10;
        } else {
            panelX = viewport.getX() + viewport.getWidth() + 10;
        }

        if (!comparisonMode) {
            if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                    && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
                viewport.zoom((float) (-delta * 0.1));
                return true;
            }
        } else {
            if (mouseX >= viewport.getX() && mouseX <= viewport.getX() + viewport.getWidth()
                    && mouseY >= viewport.getY() && mouseY <= viewport.getY() + viewport.getHeight()) {
                viewport.zoom((float) (-delta * 0.1));
                return true;
            }
            if (comparisonModel != null
                    && mouseX >= comparisonViewport.getX() && mouseX <= comparisonViewport.getX() + comparisonViewport.getWidth()
                    && mouseY >= comparisonViewport.getY() && mouseY <= comparisonViewport.getY() + comparisonViewport.getHeight()) {
                comparisonViewport.zoom((float) (-delta * 0.1));
                return true;
            }
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
        if (keyCode == 290) { // F1 - toggle comparison mode
            toggleComparisonMode();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ==================== HELPERS ====================

    public static boolean isWhiteMesh(SourceModelData.MeshData mesh) {
        return mesh.texture == null
                || (mesh.vtfKey != null && !ModelLoadManager.getColorResolver().isRegistered(mesh.vtfKey));
    }

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
