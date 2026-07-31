package transferstation.transferstation_whimsicalideas.export;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import transferstation.transferstation_whimsicalideas.client.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class ModelExporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record ExportResult(boolean success, String errorMessage) {
    }

    public record TextureEntry(String name, Path pngPath) {
    }

    public static ExportResult export(Path packageDir, Path outputDir, String format) {
        try {
            long start = System.currentTimeMillis();
            SourceModelData model = loadModelForExport(packageDir);

            if (model.meshes.isEmpty()) {
                return new ExportResult(false, "Model has no meshes to export");
            }

            List<TextureEntry> textures = TextureExporter.exportTextures(packageDir, outputDir);
            LOGGER.info("Exported {} textures to {}", textures.size(), outputDir);

            boolean exportObj = format.equals("obj") || format.equals("all");
            boolean exportBbmodel = format.equals("bbmodel") || format.equals("all");

            if (exportObj) {
                ObjWriter.write(model, textures, outputDir);
            }
            if (exportBbmodel) {
                BBModelWriter.write(model, textures, outputDir);
            }

            long elapsed = System.currentTimeMillis() - start;
            LOGGER.info("Export complete for {}: {} meshes, {} triangles, {} textures, took {}ms",
                packageDir.getFileName(), model.meshes.size(), model.totalTriangles(), textures.size(), elapsed);
            return new ExportResult(true, null);
        } catch (Exception e) {
            LOGGER.error("Export failed for {}", packageDir, e);
            return new ExportResult(false, e.getMessage());
        }
    }

    private static SourceModelData loadModelForExport(Path packageDir) throws IOException {
        Map<Path, List<Path>> dirFiles = new HashMap<>();
        Set<String> SKIP_DIR_NAMES = Set.of("lua", "materials", "scripts", "sound", "particles", "resource");
        try (Stream<Path> walk = Files.walk(packageDir, 8)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                Path parent = f.getParent();
                if (parent != null) {
                    boolean inSkipDir = false;
                    for (int i = 0; i < parent.getNameCount(); i++) {
                        if (SKIP_DIR_NAMES.contains(parent.getName(i).toString().toLowerCase())) {
                            inSkipDir = true;
                            break;
                        }
                    }
                    if (inSkipDir) continue;
                }
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl") || name.endsWith(".vvd") || name.endsWith(".dx90.vtx") || name.endsWith(".smd")) {
                    dirFiles.computeIfAbsent(parent, k -> new ArrayList<>()).add(f);
                }
            }
        }

        Path mdl = null, vvd = null, vtx = null, smd = null;
        for (var entry : dirFiles.entrySet()) {
            for (Path f : entry.getValue()) {
                String name = f.getFileName().toString().toLowerCase();
                if (name.endsWith(".mdl")) mdl = f;
                else if (name.endsWith(".vvd")) vvd = f;
                else if (name.endsWith(".dx90.vtx")) vtx = f;
                else if (name.endsWith(".smd")) smd = f;
            }
        }

        if (mdl != null && vvd != null && vtx != null) {
            return buildFromMdlTrio(mdl, vvd, vtx);
        } else if (smd != null) {
            return buildFromSmd(smd);
        } else {
            throw new IOException("No parseable model files found in " + packageDir);
        }
    }

    private static SourceModelData buildFromMdlTrio(Path mdlPath, Path vvdPath, Path vtxPath) throws IOException {
        MdlDataTypes.ParsedModel mdl = MdlParser.parse(Files.readAllBytes(mdlPath));
        VvdParser.ParsedVvd vvd = VvdParser.parse(Files.readAllBytes(vvdPath));
        VtxParser.ParsedVtx vtx = VtxParser.parse(Files.readAllBytes(vtxPath), vvd.vertices.size());

        SourceModelData data = new SourceModelData();
        data.name = mdl.header.name != null ? mdl.header.name : mdlPath.getFileName().toString().replace(".mdl", "");
        data.modelScale = 1.0f;

        for (MdlDataTypes.Bone bone : mdl.bones) {
            data.bones.add(new SourceModelData.BoneInfo(
                bone.name,
                new float[]{bone.pos[0], bone.pos[1], bone.pos[2]},
                bone.quat != null ? new float[]{bone.quat[0], bone.quat[1], bone.quat[2], bone.quat[3]} : null,
                bone.rot != null ? new float[]{bone.rot[0], bone.rot[1], bone.rot[2]} : null,
                bone.parent));
        }

        buildMeshesFromMdlTrio(mdl, vvd, vtx, data);
        computeBounds(data);
        return data;
    }

    private static void buildMeshesFromMdlTrio(MdlDataTypes.ParsedModel mdl, VvdParser.ParsedVvd vvd,
                                                VtxParser.ParsedVtx vtx, SourceModelData data) {
        List<VvdParser.StudioVertexExt> vvdVerts = vvd.vertices;
        if (vvdVerts.isEmpty()) return;

        int meshIdx = 0;
        for (int m = 0; m < vtx.meshTriangles.size(); m++) {
            List<VtxParser.VtxTriangle> tris = vtx.meshTriangles.get(m);
            if (tris.isEmpty()) continue;

            Map<Integer, Integer> vtxRemap = new HashMap<>();
            List<Float> verts = new ArrayList<>();
            List<Integer> idxs = new ArrayList<>();

            for (VtxParser.VtxTriangle tri : tris) {
                for (int v : new int[]{tri.v0, tri.v1, tri.v2}) {
                    Integer cached = vtxRemap.get(v);
                    if (cached != null) {
                        idxs.add(cached);
                        continue;
                    }
                    VvdParser.StudioVertexExt src = vvdVerts.get(v);
                    verts.add(-src.y);
                    verts.add(src.z);
                    verts.add(src.x);
                    verts.add(-src.ny);
                    verts.add(src.nz);
                    verts.add(src.nx);
                    verts.add(src.u);
                    verts.add(1.0f - src.v);
                    int newIdx = (verts.size() / 8) - 1;
                    vtxRemap.put(v, newIdx);
                    idxs.add(newIdx);
                }
            }

            float[] vertArray = new float[verts.size()];
            for (int i = 0; i < verts.size(); i++) vertArray[i] = verts.get(i);
            int[] idxArray = new int[idxs.size()];
            for (int i = 0; i < idxs.size(); i++) idxArray[i] = idxs.get(i);

            data.meshes.add(new SourceModelData.MeshData.Builder()
                .vertices(vertArray).indices(idxArray)
                .bodyPartIndex(0).modelIndex(0).materialIndex(meshIdx)
                .build());
            meshIdx++;
        }
    }

    private static SourceModelData buildFromSmd(Path smdPath) throws IOException {
        SmdParser.ParsedSmd smd = SmdParser.parse(Files.readAllBytes(smdPath));
        SourceModelData data = new SourceModelData();
        data.name = smdPath.getFileName().toString().replace(".smd", "");
        data.modelScale = 1.0f;

        for (SmdParser.SmdBone bone : smd.bones) {
            data.bones.add(new SourceModelData.BoneInfo(bone.name, new float[]{0, 0, 0}, bone.parent));
        }

        int meshIdx = 0;
        for (SmdParser.SmdMesh sm : smd.meshes) {
            if (sm.vertices.size() < 3) continue;
            List<Float> vertList = new ArrayList<>();
            List<Integer> idxList = new ArrayList<>();
            Map<String, Integer> vertCache = new HashMap<>();

            int triCount = sm.vertices.size() / 3;
            for (int t = 0; t < triCount; t++) {
                for (int v = 0; v < 3; v++) {
                    SmdParser.SmdVertex sv = sm.vertices.get(t * 3 + v);
                    String key = String.format("%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%.6f_%d",
                        sv.x, sv.y, sv.z, sv.nx, sv.ny, sv.nz, sv.u, sv.v, sv.primaryBone());
                    Integer cached = vertCache.get(key);
                    if (cached != null) {
                        idxList.add(cached);
                        continue;
                    }
                    vertList.add(-sv.y);
                    vertList.add(sv.z);
                    vertList.add(sv.x);
                    vertList.add(-sv.ny);
                    vertList.add(sv.nz);
                    vertList.add(sv.nx);
                    vertList.add(sv.u);
                    vertList.add(1.0f - sv.v);
                    int newIdx = (vertList.size() / 8) - 1;
                    vertCache.put(key, newIdx);
                    idxList.add(newIdx);
                }
            }

            float[] va = new float[vertList.size()];
            for (int i = 0; i < vertList.size(); i++) va[i] = vertList.get(i);
            int[] ia = new int[idxList.size()];
            for (int i = 0; i < idxList.size(); i++) ia[i] = idxList.get(i);

            data.meshes.add(new SourceModelData.MeshData.Builder()
                .vertices(va).indices(ia)
                .bodyPartIndex(0).modelIndex(0).materialIndex(meshIdx++)
                .build());
        }
        computeBounds(data);
        return data;
    }

    private static void computeBounds(SourceModelData data) {
        for (SourceModelData.MeshData mesh : data.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += 8) {
                float x = mesh.vertices[i], y = mesh.vertices[i + 1], z = mesh.vertices[i + 2];
                if (x < data.minX) data.minX = x;
                if (x > data.maxX) data.maxX = x;
                if (y < data.minY) data.minY = y;
                if (y > data.maxY) data.maxY = y;
                if (z < data.minZ) data.minZ = z;
                if (z > data.maxZ) data.maxZ = z;
            }
        }
    }
}
