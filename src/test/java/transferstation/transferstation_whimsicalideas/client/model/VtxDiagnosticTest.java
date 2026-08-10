package transferstation.transferstation_whimsicalideas.client.model;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 诊断性测试：解析测试模型并输出详细的三角形统计信息，
 * 用于定位哪些三角形在解析过程中被丢弃。
 */
public class VtxDiagnosticTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Path TEST_MODEL_DIR = Paths.get(
        "run", "config", "transferstation_whimsicalideas", "models",
        "0v0NekoWork_Chiffon_GothicTacMaid",
        "models", "pm"
    );

    @Test
    public void diagnoseChiffonPmModel() throws IOException {
        Path mdlFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid.mdl");
        Path vvdFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid.vvd");
        Path vtxFile = TEST_MODEL_DIR.resolve("ChiffonGothicTacMaid.dx90.vtx");

        assertTrue(Files.exists(mdlFile), "MDL file not found: " + mdlFile.toAbsolutePath());
        assertTrue(Files.exists(vvdFile), "VVD file not found: " + vvdFile.toAbsolutePath());
        assertTrue(Files.exists(vtxFile), "VTX file not found: " + vtxFile.toAbsolutePath());

        byte[] mdlData = Files.readAllBytes(mdlFile);
        byte[] vvdData = Files.readAllBytes(vvdFile);
        byte[] vtxData = Files.readAllBytes(vtxFile);

        LOGGER.info("=== File Sizes ===");
        LOGGER.info("MDL: {} bytes", mdlData.length);
        LOGGER.info("VVD: {} bytes", vvdData.length);
        LOGGER.info("VTX: {} bytes", vtxData.length);

        // Parse MDL
        MdlDataTypes.ParsedModel mdl = MdlParser.parse(mdlData);
        LOGGER.info("=== MDL Parse Results ===");
        LOGGER.info("Header: id=0x{} version={} name='{}'",
            Integer.toHexString(mdl.header.id), mdl.header.version, mdl.header.name);
        LOGGER.info("BodyParts: {}", mdl.bodyParts.size());
        for (int i = 0; i < mdl.bodyParts.size(); i++) {
            var bp = mdl.bodyParts.get(i);
            LOGGER.info("  BodyPart[{}]: name='{}' numModels={} baseIndex={}",
                i, bp.name, bp.nummodels, bp.baseIndex);
        }
        LOGGER.info("Models: {}", mdl.models.size());
        for (int i = 0; i < mdl.models.size(); i++) {
            var m = mdl.models.get(i);
            LOGGER.info("  Model[{}]: name='{}' bodypart={} numMeshes={} numVerts={} vertexindex=0x{}",
                i, m.name, m.bodypartIndex, m.nummeshes, m.numvertices, Integer.toHexString(m.vertexindex));
        }
        // Verify that model.numvertices matches sum of mesh vertex counts for that model
        for (int mi = 0; mi < mdl.models.size(); mi++) {
            var model = mdl.models.get(mi);
            int sumMeshVerts = 0;
            for (var mesh : mdl.meshes) {
                if (mesh.modelindex == mi) {
                    int end = mesh.vertexoffset + mesh.numvertices;
                    sumMeshVerts = Math.max(sumMeshVerts, end);
                }
            }
            if (model.numvertices != sumMeshVerts) {
                LOGGER.warn("  Model[{}] numvertices mismatch: header={} computed from meshes={}",
                    mi, model.numvertices, sumMeshVerts);
            }
        }
        // Check mesh vertex offsets cover all model vertices exactly once
        for (int mi = 0; mi < mdl.models.size(); mi++) {
            var model = mdl.models.get(mi);
            if (model.nummeshes == 0) continue;
            int lastEnd = 0;
            for (int meshIdx = 0; meshIdx < mdl.meshes.size(); meshIdx++) {
                var mesh = mdl.meshes.get(meshIdx);
                if (mesh.modelindex != mi) continue;
                if (mesh.vertexoffset != lastEnd) {
                    LOGGER.warn("  Model[{}] Mesh[{}]: vertexoffset {} != expected {} (previous end)",
                        mi, meshIdx, mesh.vertexoffset, lastEnd);
                }
                lastEnd = mesh.vertexoffset + mesh.numvertices;
            }
        }
        LOGGER.info("Meshes (from MDL bodyparts->models): {}", mdl.meshes.size());
        for (int i = 0; i < mdl.meshes.size(); i++) {
            var mesh = mdl.meshes.get(i);
            LOGGER.info("  Mesh[{}]: material={} modelindex={} numvertices={} vertexoffset={} meshid={}",
                i, mesh.material, mesh.modelindex, mesh.numvertices, mesh.vertexoffset, mesh.meshid);
        }
        LOGGER.info("Bones: {}", mdl.bones.size());
        LOGGER.info("Textures: {}", mdl.textures.size());
        for (int i = 0; i < mdl.textures.size(); i++) {
            LOGGER.info("  Texture[{}]: '{}' flags=0x{}", i, mdl.textures.get(i).name,
                Integer.toHexString(mdl.textures.get(i).flags));
        }
        LOGGER.info("IncludeModels: {}", mdl.includeModels);
        LOGGER.info("SkinTable ({} entries): {}", mdl.skinTable.size(), mdl.skinTable);

        // Parse VVD
        VvdParser.ParsedVvd vvd = VvdParser.parse(vvdData);
        LOGGER.info("=== VVD Parse Results ===");
        LOGGER.info("Version: {} vertices: {} fixups: {} LOD verts: {}",
            vvd.header.version, vvd.vertices.size(), vvd.fixups.size(), vvd.lodVertices.size());

        // Parse VTX with detailed logging
        LOGGER.info("=== VTX Parse Results ===");
        LOGGER.info("Calling VtxParser.parse() with vvdVerts={}", vvd.vertices.size());
        VtxParser.ParsedVtx vtx = VtxParser.parse(vtxData, vvd.vertices.size());

        LOGGER.info("VTX version: {} checksum: {} numBodyParts: {} numLODs: {}",
            vtx.version, vtx.checksum, vtx.numBodyParts, vtx.numLODs);
        LOGGER.info("VTX LOD mesh groups: {}", vtx.lodMeshTriangles.size());

        LOGGER.info("VTX meshTriangles count: {}", vtx.meshTriangles.size());
        int totalTris = 0;
        for (int i = 0; i < vtx.meshTriangles.size(); i++) {
            List<VtxParser.VtxTriangle> tris = vtx.meshTriangles.get(i);
            totalTris += tris.size();
            LOGGER.info("  Mesh[{}]: {} triangles", i, tris.size());
            if (tris.isEmpty()) continue;
            int minIdx = Integer.MAX_VALUE;
            int maxIdx = Integer.MIN_VALUE;
            for (VtxParser.VtxTriangle t : tris) {
                minIdx = Math.min(minIdx, Math.min(t.v0, Math.min(t.v1, t.v2)));
                maxIdx = Math.max(maxIdx, Math.max(t.v0, Math.max(t.v1, t.v2)));
            }
            LOGGER.info("    Vertex index range: {} - {} (VVD has {} verts)", minIdx, maxIdx, vvd.vertices.size());
            if (maxIdx >= vvd.vertices.size()) {
                LOGGER.warn("    ** WARNING: max VTX vertex index {} >= VVD vertex count {} **", maxIdx, vvd.vertices.size());
            }
        }
        LOGGER.info("Total VTX triangles: {}", totalTris);

        // VTX vs MDL mesh count comparison
        LOGGER.info("=== Alignment Check ===");
        LOGGER.info("MDL meshes: {}  VTX meshTriangles: {}", mdl.meshes.size(), vtx.meshTriangles.size());
        if (mdl.meshes.size() != vtx.meshTriangles.size()) {
            LOGGER.warn("** MISMATCH: VTX and MDL mesh counts differ! **");
            LOGGER.warn("  This causes lockstep desync in buildMeshes");
        }

        // Now simulate buildMeshes logic
        LOGGER.info("=== BuildMeshes Simulation ===");
        simulateBuildMeshes(mdl, vvd, vtx);

        // Check for any in-memory issues
        LOGGER.info("=== Memory Usage ===");
        Runtime rt = Runtime.getRuntime();
        LOGGER.info("Used: {} MB", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
    }

    @Test
    public void diagnoseTextures() throws IOException {
        Path modelDir = TEST_MODEL_DIR;
        Path rootDir = modelDir.getParent().getParent(); // up to 0v0NekoWork_Chiffon_GothicTacMaid
        Path materialsDir = rootDir.resolve("materials");
        Path mdlFile = modelDir.resolve("chiffongothictacmaid.mdl");
        Path vvdFile = modelDir.resolve("chiffongothictacmaid.vvd");
        Path vtxFile = modelDir.resolve("ChiffonGothicTacMaid.dx90.vtx");

        assertTrue(Files.exists(mdlFile), "MDL file not found: " + mdlFile.toAbsolutePath());
        assertTrue(Files.exists(materialsDir), "Materials dir not found: " + materialsDir.toAbsolutePath());

        byte[] mdlData = Files.readAllBytes(mdlFile);
        byte[] vvdData = Files.readAllBytes(vvdFile);
        byte[] vtxData = Files.readAllBytes(vtxFile);

        MdlDataTypes.ParsedModel mdl = MdlParser.parse(mdlData);
        VvdParser.ParsedVvd vvd = VvdParser.parse(vvdData);
        VtxParser.ParsedVtx vtx = VtxParser.parse(vtxData, vvd.vertices.size());

        // Load all VMT files
        LOGGER.info("=== Loading VMT Files ===");
        Map<String, VmtParser.VmtMaterial> vmtCache = new java.util.HashMap<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(materialsDir, 8)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                if (f.getFileName().toString().toLowerCase().endsWith(".vmt")) {
                    String relPath = materialsDir.relativize(f).toString().replace('\\', '/').toLowerCase();
                    if (relPath.endsWith(".vmt")) relPath = relPath.substring(0, relPath.length() - 4);
                    VmtParser.VmtMaterial mat = VmtParser.parse(Files.readAllBytes(f));
                    vmtCache.put(relPath, mat);
                    LOGGER.info("  VMT: {} -> nocull={} translucent={} alphatest={} basetexture={}",
                        relPath, mat.isNoCull(), mat.isTransparent(), mat.isAlphaTest(), mat.getFullBaseTexturePath());
                }
            }
        }

        LOGGER.info("=== MDL Textures ===");
        for (int i = 0; i < mdl.textures.size(); i++) {
            LOGGER.info("  Texture[{}]: '{}' flags=0x{}", i, mdl.textures.get(i).name, Integer.toHexString(mdl.textures.get(i).flags));
        }

        LOGGER.info("=== Skin Table ===");
        LOGGER.info("  numskinref={} numskinfamilies={} entries={}", mdl.header.numskinref, mdl.header.numskinfamilies, mdl.skinTable.size());
        if (!mdl.skinTable.isEmpty()) {
            LOGGER.info("  skinTable={}", mdl.skinTable);
        }

        LOGGER.info("=== VTX Mesh Triangle Counts ===");
        for (int i = 0; i < vtx.meshTriangles.size(); i++) {
            LOGGER.info("  VTX Mesh[{}]: {} triangles", i, vtx.meshTriangles.get(i).size());
        }

        LOGGER.info("=== Mesh → Texture Resolution ===");
        LOGGER.info("MDL cdTextures={}", mdl.cdTextures);

        for (int meshIdx = 0; meshIdx < mdl.meshes.size(); meshIdx++) {
            var mesh = mdl.meshes.get(meshIdx);
            int materialIdx = mesh.material;
            int texIndex = materialIdx;

            if (!mdl.skinTable.isEmpty() && mdl.header.numskinref > 0) {
                int wrapped = materialIdx >= 0 ? materialIdx % mdl.header.numskinref : 0;
                if (wrapped < mdl.skinTable.size()) {
                    texIndex = mdl.skinTable.get(wrapped);
                }
            }

            String texName = (texIndex >= 0 && texIndex < mdl.textures.size())
                ? mdl.textures.get(texIndex).name : "(null)";
            String texNorm = texName.replace('\\', '/').toLowerCase();
            if (texNorm.endsWith(".vtf") || texNorm.endsWith(".vmt")) {
                texNorm = texNorm.substring(0, texNorm.length() - 4);
            }

            // Build candidate paths with cdTextures
            java.util.List<String> candidates = new java.util.ArrayList<>();
            candidates.add(texNorm);
            for (String cdTex : mdl.cdTextures) {
                String prefix = cdTex.replace('\\', '/').toLowerCase();
                if (!prefix.endsWith("/")) prefix += "/";
                if (!texNorm.startsWith(prefix)) {
                    candidates.add(prefix + texNorm);
                }
            }

            // Try to find matching VMT
            String matchedVmt = null;
            VmtParser.VmtMaterial matchedMat = null;
            for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                String fullBt = e.getValue().getFullBaseTexturePath();
                if (fullBt != null) {
                    for (String cand : candidates) {
                        if (fullBt.equals(cand) || fullBt.endsWith("/" + cand) || cand.endsWith("/" + fullBt)) {
                            matchedMat = e.getValue();
                            matchedVmt = e.getKey();
                            break;
                        }
                    }
                }
                if (matchedMat == null) {
                    String bt = e.getValue().getBaseTexture();
                    if (bt != null) {
                        String btNorm = bt.replace('\\', '/').toLowerCase();
                        if (btNorm.endsWith(".vtf")) btNorm = btNorm.substring(0, btNorm.length() - 4);
                        for (String cand : candidates) {
                            if (btNorm.equals(cand) || btNorm.endsWith("/" + cand) || cand.endsWith("/" + btNorm)) {
                                matchedMat = e.getValue();
                                matchedVmt = e.getKey();
                                break;
                            }
                        }
                    }
                }
                if (matchedMat != null) break;
            }

            // Fallback: match by VMT key (file path) when $basetexture differs
            if (matchedMat == null) {
                for (Map.Entry<String, VmtParser.VmtMaterial> e : vmtCache.entrySet()) {
                    String vmtKey = e.getKey().toLowerCase();
                    for (String cand : candidates) {
                        if (vmtKey.equals(cand) || vmtKey.endsWith("/" + cand) || cand.endsWith("/" + vmtKey)) {
                            matchedMat = e.getValue();
                            matchedVmt = e.getKey();
                            break;
                        }
                    }
                    if (matchedMat != null) break;
                }
            }

            int triCount = (meshIdx < vtx.meshTriangles.size()) ? vtx.meshTriangles.get(meshIdx).size() : -1;
            LOGGER.info("  Mesh[{}]: material={} texIndex={} texName='{}' tris={} -> VMT match={}",
                meshIdx, materialIdx, texIndex, texName, triCount, matchedVmt != null ? matchedVmt : "NOT FOUND");
            if (matchedMat != null) {
                LOGGER.info("    shader={} noCull={} translucent={} alphaTest={} selfIllum={} phong={} halfLambert={}",
                    matchedMat.shader, matchedMat.isNoCull(), matchedMat.isTransparent(),
                    matchedMat.isAlphaTest(), matchedMat.isSelfIllum(), matchedMat.hasPhong(), matchedMat.isHalfLambert());
                LOGGER.info("    $basetexture='{}' $cdmaterials='{}' fullPath='{}'",
                    matchedMat.getBaseTexture(), matchedMat.getCdMaterials(), matchedMat.getFullBaseTexturePath());
            } else {
                LOGGER.warn("    ** WARNING: No VMT matched for this mesh texture!");
            }
        }
    }

    private void simulateBuildMeshes(MdlDataTypes.ParsedModel mdl,
                                      VvdParser.ParsedVvd vvd,
                                      VtxParser.ParsedVtx vtx) {
        var vvdVerts = vvd.vertices;
        int vtxMeshCount = vtx.meshTriangles.size();
        int mdlMeshCount = mdl.meshes.size();
        int mdlModelCount = mdl.models.size();

        LOGGER.info("BuildMeshes: VVD={} VTX meshes={} MDL meshes={} MDL models={}",
            vvdVerts.size(), vtxMeshCount, mdlMeshCount, mdlModelCount);

        boolean vvdTightlyPacked = vvd.fixups.isEmpty();
        LOGGER.info("VVD fixups: {} (tightlyPacked={})", vvd.fixups.size(), vvdTightlyPacked);

        // Track where triangles get lost
        int totalPossibleTris = 0;
        int totalOutputTris = 0;
        int oobFilteredTris = 0;
        int totalMeshesBuilt = 0;

        int vtxMeshCursor = 0;
        int mdlMeshCursor = 0;
        int vvdAccumBase = 0;
        boolean[] bodygroupActiveModelSeen = new boolean[mdl.bodyParts.size()];

        for (int bpIdx = 0; bpIdx < mdl.bodyParts.size(); bpIdx++) {
            var bp = mdl.bodyParts.get(bpIdx);
            for (int modelIdx = 0; modelIdx < mdlModelCount; modelIdx++) {
                var model = mdl.models.get(modelIdx);
                if (model.bodypartIndex != bpIdx) continue;

                boolean shadowOnly = bodygroupActiveModelSeen[bpIdx];
                if (model.nummeshes > 0) bodygroupActiveModelSeen[bpIdx] = true;

                int vvdModelBase;
                if (vvdTightlyPacked) {
                    vvdModelBase = vvdAccumBase;
                } else {
                    int rawBase = model.vertexindex - vvd.header.vertexDataStart;
                    vvdModelBase = (rawBase < 0 ? 0 : rawBase) / 48;
                }
                if (vvdModelBase < 0) vvdModelBase = 0;
                if (vvdModelBase > vvdVerts.size()) vvdModelBase = vvdVerts.size();

                LOGGER.info("Model[{}] '{}' bpIdx={} shadowOnly={} vvdModelBase={} numMeshes={}",
                    modelIdx, model.name, bpIdx, shadowOnly, vvdModelBase, model.nummeshes);

                for (int meshLocalIdx = 0; meshLocalIdx < model.nummeshes; meshLocalIdx++) {
                    int globalMeshIdx = vtxMeshCursor++;
                    int alignedMdlMeshIdx = mdlMeshCursor++;

                    int vertexOffset = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).vertexoffset : 0;
                    int meshNumVertices = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).numvertices : 0;

                    List<VtxParser.VtxTriangle> tris = (globalMeshIdx < vtxMeshCount) ?
                        vtx.meshTriangles.get(globalMeshIdx) : List.of();

                    LOGGER.info("  Mesh[vtx={} mdl={}] shadowOnly={} tris={} vertOffset={} meshNumVerts={}",
                        globalMeshIdx, alignedMdlMeshIdx, shadowOnly, tris.size(), vertexOffset, meshNumVertices);

                    if (shadowOnly) {
                        LOGGER.info("    -> SKIPPED (bodygroup variant)");
                        continue;
                    }

                    if (tris.isEmpty()) {
                        LOGGER.info("    -> SKIPPED (no triangles)");
                        continue;
                    }

                    totalPossibleTris += tris.size();

                    // Check each triangle
                    int meshOobCount = 0;
                    for (int ti = 0; ti < tris.size(); ti++) {
                        VtxParser.VtxTriangle tri = tris.get(ti);
                        int vvdIdx0 = resolveVvdIndex(tri.v0, vvdModelBase, vertexOffset,
                            vvdVerts.size(), vvd.fixups, 0);
                        int vvdIdx1 = resolveVvdIndex(tri.v1, vvdModelBase, vertexOffset,
                            vvdVerts.size(), vvd.fixups, 0);
                        int vvdIdx2 = resolveVvdIndex(tri.v2, vvdModelBase, vertexOffset,
                            vvdVerts.size(), vvd.fixups, 0);

                        boolean oob = vvdIdx0 < 0 || vvdIdx0 >= vvdVerts.size()
                                   || vvdIdx1 < 0 || vvdIdx1 >= vvdVerts.size()
                                   || vvdIdx2 < 0 || vvdIdx2 >= vvdVerts.size();
                        if (oob) {
                            meshOobCount++;
                            if (meshOobCount <= 3) {
                                LOGGER.warn("    Tri[{}] OOB: v0={}->{} v1={}->{} v2={}->{}",
                                    ti, tri.v0, vvdIdx0, tri.v1, vvdIdx1, tri.v2, vvdIdx2);
                            }
                        }
                    }
                    oobFilteredTris += meshOobCount;
                    int meshOutputTris = tris.size() - meshOobCount;
                    totalOutputTris += meshOutputTris;
                    totalMeshesBuilt++;

                    if (meshOobCount > 0) {
                        LOGGER.warn("    -> {}/{} triangles OOB-filtered", meshOobCount, tris.size());
                    }
                    LOGGER.info("    -> {} output triangles", meshOutputTris);
                }
                if (vvdTightlyPacked) {
                    vvdAccumBase += model.numvertices;
                }
            }
        }

        LOGGER.info("=== Summary ===");
        LOGGER.info("Total possible triangles: {}", totalPossibleTris);
        LOGGER.info("OOB filtered triangles:  {}", oobFilteredTris);
        LOGGER.info("Output triangles:        {}", totalOutputTris);
        LOGGER.info("Meshes built:            {}", totalMeshesBuilt);

        // Check for any bodyparts that had multiple models
        for (int bpIdx = 0; bpIdx < mdl.bodyParts.size(); bpIdx++) {
            if (bodygroupActiveModelSeen[bpIdx]) {
                final int fbpi = bpIdx;
                long modelCount = mdl.models.stream()
                    .filter(m -> m.bodypartIndex == fbpi)
                    .count();
                if (modelCount > 1) {
                    LOGGER.warn("BodyPart[{}] '{}' has {} models - only first emits geometry!",
                        bpIdx, mdl.bodyParts.get(bpIdx).name, modelCount);
                }
            }
        }
    }

    private int resolveVvdIndex(int origMeshVertId, int vvdModelBase, int vertexOffset,
                                 int vvdVertexCount, List<VvdParser.VvdFixup> fixups,
                                 int lodLevel) {
        if (origMeshVertId < 0) return -1;
        if (fixups != null && !fixups.isEmpty()) {
            int id = origMeshVertId;
            for (VvdParser.VvdFixup f : fixups) {
                if (f.numVertexes <= 0 || f.lodIndex != lodLevel) continue;
                if (id < f.numVertexes) {
                    int raw = f.sourceVertexID + id;
                    return (raw >= 0 && raw < vvdVertexCount) ? raw : -1;
                }
                id -= f.numVertexes;
            }
            return -1;
        }
        int adjusted = vvdModelBase + vertexOffset + origMeshVertId;
        if (adjusted >= 0 && adjusted < vvdVertexCount) return adjusted;
        if (origMeshVertId >= 0 && origMeshVertId < vvdVertexCount) return origMeshVertId;
        return -1;
    }

    /**
     * 几何证据测试：构建每个 mesh 的世界坐标三角形，检测"超长边"三角形。
     * "手连脚"形态表现为某条边长远超该 mesh 自身包围盒对角线。若 Java 数据层就存在，
     * 则可排除 C++ 侧上传/索引拼接差异，根因回溯到 resolveVvdIndex/fixup/vertexOffset。
     */
    @Test
    public void diagnoseLongEdgeTriangles() throws IOException {
        Path mdlFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid.mdl");
        Path vvdFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid.vvd");
        Path vtxFile = TEST_MODEL_DIR.resolve("ChiffonGothicTacMaid.dx90.vtx");
        assertTrue(Files.exists(mdlFile), "MDL file not found: " + mdlFile.toAbsolutePath());

        MdlDataTypes.ParsedModel mdl = MdlParser.parse(Files.readAllBytes(mdlFile));
        VvdParser.ParsedVvd vvd = VvdParser.parse(Files.readAllBytes(vvdFile));
        VtxParser.ParsedVtx vtx = VtxParser.parse(Files.readAllBytes(vtxFile), vvd.vertices.size());

        List<VvdParser.StudioVertexExt> verts = vvd.vertices;
        boolean vvdTightlyPacked = vvd.fixups.isEmpty();
        int vvdAccumBase = 0;
        int vtxMeshCursor = 0;
        int mdlMeshCursor = 0;
        int mdlMeshCount = mdl.meshes.size();
        int vtxMeshCount = vtx.meshTriangles.size();
        int mdlModelCount = mdl.models.size();

        long longEdgeTris = 0;
        long totalTris = 0;
        java.util.Map<Integer, Integer> longEdgesByMesh = new java.util.TreeMap<>();
        int worstMesh = -1;
        double worstRatio = 0;

        for (int bpIdx = 0; bpIdx < mdl.bodyParts.size(); bpIdx++) {
            for (int modelIdx = 0; modelIdx < mdlModelCount; modelIdx++) {
                var model = mdl.models.get(modelIdx);
                if (model.bodypartIndex != bpIdx) continue;

                int vvdModelBase;
                if (vvdTightlyPacked) {
                    vvdModelBase = vvdAccumBase;
                } else {
                    int rawBase = model.vertexindex - vvd.header.vertexDataStart;
                    vvdModelBase = (rawBase < 0 ? 0 : rawBase) / 48;
                }
                if (vvdModelBase < 0) vvdModelBase = 0;
                if (vvdModelBase > verts.size()) vvdModelBase = verts.size();

                for (int meshLocalIdx = 0; meshLocalIdx < model.nummeshes; meshLocalIdx++) {
                    int globalMeshIdx = vtxMeshCursor++;
                    int alignedMdlMeshIdx = mdlMeshCursor++;

                    int vertexOffset = (alignedMdlMeshIdx < mdlMeshCount) ?
                        mdl.meshes.get(alignedMdlMeshIdx).vertexoffset : 0;
                    List<VtxParser.VtxTriangle> tris = (globalMeshIdx < vtxMeshCount) ?
                        vtx.meshTriangles.get(globalMeshIdx) : List.of();

                    if (tris.isEmpty()) continue;

                    // 先映射到世界空间 VVD 顶点，构建该 mesh 的包络。
                    float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
                    float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
                    int triCount = 0;
                    for (VtxParser.VtxTriangle tri : tris) {
                        int v0 = resolveVvdIndex(tri.v0, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        int v1 = resolveVvdIndex(tri.v1, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        int v2 = resolveVvdIndex(tri.v2, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        if (v0 < 0 || v1 < 0 || v2 < 0 ||
                            v0 >= verts.size() || v1 >= verts.size() || v2 >= verts.size()) continue;
                        triCount++;
                        VvdParser.StudioVertexExt a = verts.get(v0);
                        VvdParser.StudioVertexExt b = verts.get(v1);
                        VvdParser.StudioVertexExt c = verts.get(v2);
                        minX = Math.min(minX, Math.min(a.x, Math.min(b.x, c.x)));
                        minY = Math.min(minY, Math.min(a.y, Math.min(b.y, c.y)));
                        minZ = Math.min(minZ, Math.min(a.z, Math.min(b.z, c.z)));
                        maxX = Math.max(maxX, Math.max(a.x, Math.max(b.x, c.x)));
                        maxY = Math.max(maxY, Math.max(a.y, Math.max(b.y, c.y)));
                        maxZ = Math.max(maxZ, Math.max(a.z, Math.max(b.z, c.z)));
                    }
                    if (triCount == 0) continue;

                    double bx = maxX - minX, by = maxY - minY, bz = maxZ - minZ;
                    double bboxDiag = Math.sqrt(bx * bx + by * by + bz * bz);
                    // 上限 = 0.6 倍自身包围盒对角线。真正的手连脚长边远大于此。
                    double limit = Math.max(bboxDiag * 0.6, 5.0);

                    int meshLong = 0;
                    int meshTotal = 0;
                    double meshWorstRatio = 0;
                    for (VtxParser.VtxTriangle tri : tris) {
                        int v0 = resolveVvdIndex(tri.v0, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        int v1 = resolveVvdIndex(tri.v1, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        int v2 = resolveVvdIndex(tri.v2, vvdModelBase, vertexOffset,
                            verts.size(), vvd.fixups, 0);
                        if (v0 < 0 || v1 < 0 || v2 < 0 ||
                            v0 >= verts.size() || v1 >= verts.size() || v2 >= verts.size()) continue;
                        meshTotal++;
                        VvdParser.StudioVertexExt a = verts.get(v0);
                        VvdParser.StudioVertexExt b = verts.get(v1);
                        VvdParser.StudioVertexExt c = verts.get(v2);
                        double e01 = dist(a, b);
                        double e12 = dist(b, c);
                        double e20 = dist(c, a);
                        double maxE = Math.max(e01, Math.max(e12, e20));
                        if (maxE > limit) {
                            meshLong++;
                            if (bboxDiag > 1e-6) {
                                meshWorstRatio = Math.max(meshWorstRatio, maxE / bboxDiag);
                            }
                        }
                    }
                    totalTris += meshTotal;
                    if (meshLong > 0) {
                        longEdgeTris += meshLong;
                        longEdgesByMesh.put(globalMeshIdx, meshLong);
                        LOGGER.warn("  Mesh[{}] vertOffset={} bboxDiag={} limit={} longEdgeTris={}/{} worstRatio={}",
                            globalMeshIdx, vertexOffset, String.format("%.2f", bboxDiag),
                            String.format("%.2f", limit), meshLong, meshTotal,
                            String.format("%.1f", meshWorstRatio));
                        if (meshWorstRatio > worstRatio) {
                            worstRatio = meshWorstRatio;
                            worstMesh = globalMeshIdx;
                        }
                    }
                }
                if (vvdTightlyPacked) {
                    vvdAccumBase += model.numvertices;
                }
            }
        }

        LOGGER.info("=== LongEdge summary: {}/{} triangles span >0.6x their own mesh bbox diag ===", longEdgeTris, totalTris);
        LOGGER.info("Meshes with long-edge tris: {}", longEdgesByMesh);
        if (worstMesh >= 0) {
            LOGGER.warn("Worst mesh[{}]: maxEdge/bboxDiag = {:.1f}", worstMesh, worstRatio);
        }
    }

    private static double dist(VvdParser.StudioVertexExt a, VvdParser.StudioVertexExt b) {
        float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    public void diagnoseChiffonPmNopussyModel() throws IOException {
        Path mdlFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid_nopussy.mdl");
        Path vvdFile = TEST_MODEL_DIR.resolve("chiffongothictacmaid_nopussy.vvd");
        Path vtxFile = TEST_MODEL_DIR.resolve("ChiffonGothicTacMaid_nopussy.dx90.vtx");

        if (!Files.exists(mdlFile)) {
            LOGGER.info("Skipping nopussy variant - file not found");
            return;
        }

        byte[] mdlData = Files.readAllBytes(mdlFile);
        byte[] vvdData = Files.readAllBytes(vvdFile);
        byte[] vtxData = Files.readAllBytes(vtxFile);

        MdlDataTypes.ParsedModel mdl = MdlParser.parse(mdlData);
        VvdParser.ParsedVvd vvd = VvdParser.parse(vvdData);
        VtxParser.ParsedVtx vtx = VtxParser.parse(vtxData, vvd.vertices.size());

        LOGGER.info("=== Nopussy Model ===");
        LOGGER.info("MDL version={} bodyParts={} models={} meshes={} bones={} textures={}",
            mdl.header.version, mdl.bodyParts.size(), mdl.models.size(),
            mdl.meshes.size(), mdl.bones.size(), mdl.textures.size());
        LOGGER.info("VVD vertices={} fixups={}", vvd.vertices.size(), vvd.fixups.size());
        LOGGER.info("VTX meshTriangles={}", vtx.meshTriangles.size());

        int totalTris = 0;
        for (int i = 0; i < vtx.meshTriangles.size(); i++) {
            var tris = vtx.meshTriangles.get(i);
            totalTris += tris.size();
            LOGGER.info("  VTX Mesh[{}]: {} triangles", i, tris.size());
        }
        LOGGER.info("Total VTX triangles: {}", totalTris);

        simulateBuildMeshes(mdl, vvd, vtx);
    }

    @Test
    public void diagnoseArmsModel() throws IOException {
        Path armsDir = TEST_MODEL_DIR.getParent().resolve("arms");
        Path mdlFile = armsDir.resolve("NekoWork_chiffon_arms.mdl");
        Path vvdFile = armsDir.resolve("NekoWork_chiffon_arms.vvd");
        Path vtxFile = armsDir.resolve("NekoWork_chiffon_arms.dx90.vtx");

        if (!Files.exists(mdlFile)) {
            LOGGER.info("Skipping arms - file not found");
            return;
        }

        byte[] mdlData = Files.readAllBytes(mdlFile);
        byte[] vvdData = Files.readAllBytes(vvdFile);
        byte[] vtxData = Files.readAllBytes(vtxFile);

        MdlDataTypes.ParsedModel mdl = MdlParser.parse(mdlData);
        VvdParser.ParsedVvd vvd = VvdParser.parse(vvdData);
        VtxParser.ParsedVtx vtx = VtxParser.parse(vtxData, vvd.vertices.size());

        LOGGER.info("=== Arms Model ===");
        LOGGER.info("MDL version={} bodyParts={} models={} meshes={} bones={} textures={}",
            mdl.header.version, mdl.bodyParts.size(), mdl.models.size(),
            mdl.meshes.size(), mdl.bones.size(), mdl.textures.size());
        LOGGER.info("VVD vertices={} fixups={}", vvd.vertices.size(), vvd.fixups.size());
        LOGGER.info("VTX meshTriangles={}", vtx.meshTriangles.size());

        int totalTris = 0;
        for (int i = 0; i < vtx.meshTriangles.size(); i++) {
            var tris = vtx.meshTriangles.get(i);
            totalTris += tris.size();
            LOGGER.info("  VTX Mesh[{}]: {} triangles", i, tris.size());
        }
        LOGGER.info("Total VTX triangles: {}", totalTris);

        simulateBuildMeshes(mdl, vvd, vtx);
    }
}
