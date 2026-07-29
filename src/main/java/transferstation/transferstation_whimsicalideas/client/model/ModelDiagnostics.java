package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public class ModelDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class ModelGroup {
        public Path dir;
        public String baseName;
        public Path mdl;
        public Path vvd;
        public Path vtx;
        public Path phy;

        public boolean isComplete() {
            return mdl != null && vvd != null && vtx != null;
        }
    }

    public static class DiagnosticResult {
        public String modelName;
        public boolean complete;
        public boolean hasPhy;
        public List<String> warnings = new ArrayList<>();
        public List<String> infoFields = new ArrayList<>();
        public Map<String, Integer> checksums = new LinkedHashMap<>();

        public boolean hasIssues() {
            return !warnings.isEmpty() || !complete;
        }
    }

    public static List<ModelGroup> findModelGroups(Path rootDir) throws IOException {
        Map<String, ModelGroup> groups = new LinkedHashMap<>();
        List<Path> modelFiles = new ArrayList<>();

        try (Stream<Path> files = Files.walk(rootDir, 16)) {
            modelFiles = files.filter(Files::isRegularFile).toList();
        }

        for (Path f : modelFiles) {
            String lower = f.getFileName().toString().toLowerCase();
            String base = extractBaseName(lower);
            if (base == null) continue;

            Path relDir = rootDir.relativize(f.getParent());
            String key = relDir.toString().isEmpty() ? base : relDir + "/" + base;
            key = key.replace('\\', '/');

            String effectiveBase = base;
            ModelGroup g = groups.computeIfAbsent(key, k -> {
                ModelGroup mg = new ModelGroup();
                mg.dir = f.getParent();
                mg.baseName = effectiveBase;
                return mg;
            });

            if (lower.endsWith(".mdl")) g.mdl = f;
            else if (lower.endsWith(".vvd")) g.vvd = f;
            else if (lower.endsWith(".dx90.vtx")) g.vtx = f;
            else if (lower.endsWith(".phy")) g.phy = f;
            else if (lower.endsWith(".smd")) g.mdl = f;
        }

        return new ArrayList<>(groups.values());
    }

    private static String extractBaseName(String lower) {
        if (lower.endsWith(".mdl")) {
            return lower.substring(0, lower.length() - 4);
        } else if (lower.endsWith(".vvd")) {
            return lower.substring(0, lower.length() - 4);
        } else if (lower.endsWith(".dx90.vtx")) {
            return lower.substring(0, lower.length() - 9);
        } else if (lower.endsWith(".phy")) {
            return lower.substring(0, lower.length() - 4);
        } else if (lower.endsWith(".smd")) {
            return lower.substring(0, lower.length() - 4);
        }
        return null;
    }

    public static DiagnosticResult diagnoseGroup(ModelGroup group) {
        DiagnosticResult result = new DiagnosticResult();
        result.modelName = group.baseName;
        result.complete = group.isComplete();
        result.hasPhy = group.phy != null;

        diagnoseFile(group.mdl, "MDL", result);
        diagnoseFile(group.vvd, "VVD", result);
        diagnoseFile(group.vtx, "VTX", result);
        diagnoseFile(group.phy, "PHY", result);

        checkChecksumConsistency(group, result);

        return result;
    }

    public static List<DiagnosticResult> diagnoseDirectory(Path rootDir) throws IOException {
        List<ModelGroup> groups = findModelGroups(rootDir);
        List<DiagnosticResult> results = new ArrayList<>();
        for (ModelGroup g : groups) {
            results.add(diagnoseGroup(g));
        }
        return results;
    }

    private static void diagnoseFile(Path path, String type, DiagnosticResult result) {
        if (path == null) {
            result.warnings.add("[" + type + "] MISSING");
            return;
        }

        try {
            long size = Files.size(path);
            byte[] data = Files.readAllBytes(path);
            result.infoFields.add(type + " size: " + size + " bytes");

            switch (type) {
                case "MDL" -> diagnoseMdl(data, result);
                case "VVD" -> diagnoseVvd(data, result);
                case "VTX" -> diagnoseVtx(data, result);
                case "PHY" -> diagnosePhy(data, result);
            }
        } catch (Exception e) {
            result.warnings.add("[" + type + "] Error reading: " + e.getMessage());
        }
    }

    private static void diagnoseMdl(byte[] data, DiagnosticResult result) {
        try {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            MdlDataTypes.ParsedModel mdl = strategy.parseMdl(data);
            MdlDataTypes.Header h = mdl.header;

            result.checksums.put("MDL", h.checksum);

            result.infoFields.add("MDL version: " + h.version);
            if (h.version < 44 || h.version > 53) {
                result.warnings.add("Unusual MDL version: " + h.version);
            }
            result.infoFields.add("MDL name: " + h.name);
            result.infoFields.add("Bones: " + h.numbones);
            result.infoFields.add("BodyParts: " + h.numbodyparts);
            result.infoFields.add("Textures: " + h.numtextures);
            result.infoFields.add("IncludeModels: " + h.numincludemodels);
            result.infoFields.add("LocalAnim: " + h.numlocalanim);
            result.infoFields.add("LocalSeq: " + h.numlocalseq);
            result.infoFields.add("SkinRef: " + h.numskinref);
            result.infoFields.add("SkinFamilies: " + h.numskinfamilies);

            if (h.vertexbase != 0 || h.offsetbase != 0) {
                result.warnings.add("vertexbase=" + h.vertexbase + " or offsetbase=" + h.offsetbase + " non-zero (may indicate GPU skinning)");
            }
            if (h.studiohdr2index > 0) {
                result.infoFields.add("studiohdr2 present at 0x" + Integer.toHexString(h.studiohdr2index));
            }

            if (h.numbodyparts == 0) {
                result.warnings.add("No body parts");
            } else {
                int totalModels = mdl.models.size();
                int totalMeshes = mdl.meshes.size();
                int totalVerticesFromMdl = 0;
                for (MdlDataTypes.Model m : mdl.models) {
                    totalVerticesFromMdl += m.numvertices;
                }
                result.infoFields.add("Total Models: " + totalModels);
                result.infoFields.add("Total Meshes: " + totalMeshes);
                result.infoFields.add("Total Vertices (MDL): " + totalVerticesFromMdl);

                for (int i = 0; i < mdl.bodyParts.size(); i++) {
                    MdlDataTypes.BodyPart bp = mdl.bodyParts.get(i);
                    String bpName = (bp.name != null && !bp.name.isEmpty()) ? bp.name : "(unnamed)";
                    result.infoFields.add("  BodyPart[" + i + "]: '" + bpName + "' " + bp.nummodels + " models, baseIndex=" + bp.baseIndex);
                    for (MdlDataTypes.Model m : mdl.models) {
                        if (m.bodypartIndex == i) {
                            result.infoFields.add("    Model: '" + m.name + "' " + m.nummeshes + " meshes, " + m.numvertices + " vertices");
                        }
                    }
                }

                if (h.numskinref > 0 && h.numskinfamilies > 1) {
                    result.infoFields.add("Skin Families: " + h.numskinfamilies + " (ref=" + h.numskinref + ")");
                }
            }

        } catch (Exception e) {
            result.warnings.add("[MDL] Parse error: " + e.getMessage());
        }
    }

    private static void diagnoseVvd(byte[] data, DiagnosticResult result) {
        try {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            VvdParser.ParsedVvd vvd = strategy.parseVvd(data);
            VvdParser.VvdHeader h = vvd.header;

            result.checksums.put("VVD", h.checksum);

            result.infoFields.add("VVD version: " + h.version);
            if (h.version != 4) {
                result.warnings.add("Unusual VVD version: " + h.version + " (expected 4)");
            }
            result.infoFields.add("VVD LODs: " + h.numLODs);
            result.infoFields.add("VVD LOD0 vertices: " + h.numLODVertices[0]);
            result.infoFields.add("VVD fixups: " + h.numFixups);

            int totalVerts = h.numLODVertices[0];
            int expectedMin = h.vertexDataStart + totalVerts * 48;
            if (expectedMin > data.length) {
                result.warnings.add("Vertex data exceeds file: " + expectedMin + " > " + data.length);
            }
            if (h.tangentDataStart > 0 && h.tangentDataStart + totalVerts * 16 > data.length) {
                result.warnings.add("Tangent data exceeds file");
            }

            if (!vvd.vertices.isEmpty()) {
                float minX = 0, maxX = 0, minY = 0, maxY = 0, minZ = 0, maxZ = 0;
                boolean first = true;
                for (VvdParser.StudioVertexExt v : vvd.vertices) {
                    if (first) {
                        minX = maxX = v.x; minY = maxY = v.y; minZ = maxZ = v.z;
                        first = false;
                    } else {
                        if (v.x < minX) minX = v.x; if (v.x > maxX) maxX = v.x;
                        if (v.y < minY) minY = v.y; if (v.y > maxY) maxY = v.y;
                        if (v.z < minZ) minZ = v.z; if (v.z > maxZ) maxZ = v.z;
                    }
                }
                result.infoFields.add("VVD bounds X: [" + String.format("%.1f", minX) + ", " + String.format("%.1f", maxX) + "]");
                result.infoFields.add("VVD bounds Y: [" + String.format("%.1f", minY) + ", " + String.format("%.1f", maxY) + "]");
                result.infoFields.add("VVD bounds Z: [" + String.format("%.1f", minZ) + ", " + String.format("%.1f", maxZ) + "]");
            }

        } catch (Exception e) {
            result.warnings.add("[VVD] Parse error: " + e.getMessage());
        }
    }

    private static void diagnoseVtx(byte[] data, DiagnosticResult result) {
        try {
            ModelParserStrategy strategy = ModelParserProvider.getStrategy();
            VtxParser.ParsedVtx vtx = strategy.parseVtx(data);

            result.checksums.put("VTX", vtx.checksum);

            result.infoFields.add("VTX version: " + vtx.version);
            if (vtx.version != 7) {
                result.warnings.add("Unusual VTX version: " + vtx.version + " (expected 7)");
            }
            result.infoFields.add("VTX LODs: " + vtx.numLODs);
            result.infoFields.add("VTX BodyParts: " + vtx.numBodyParts);

            int totalMeshes = vtx.meshTriangles.size();
            int totalTriangles = 0;
            for (List<VtxParser.VtxTriangle> tris : vtx.meshTriangles) {
                totalTriangles += tris.size();
            }
            result.infoFields.add("VTX Meshes (LOD0): " + totalMeshes);
            result.infoFields.add("VTX Triangles (LOD0): " + totalTriangles);

        } catch (Exception e) {
            result.warnings.add("[VTX] Parse error: " + e.getMessage());
        }
    }

    private static void diagnosePhy(byte[] data, DiagnosticResult result) {
        try {
            PhyParser.ParsedPhy phy;
            if (GmodNativeBridge.isAvailable()) {
                phy = new WindowsNativeModelParserStrategy().parsePhy(data);
            } else {
                phy = PhyParser.parse(data);
            }

            result.checksums.put("PHY", phy.checksum);

            result.infoFields.add("PHY size: " + phy.size);
            result.infoFields.add("PHY ID: " + phy.id);
            result.infoFields.add("PHY solids: " + phy.solidCount);

            if (!"VPHY".equals(phy.id) && !"PHYS".equals(phy.id) && !phy.valid) {
                result.warnings.add("Unusual PHY ID: " + phy.id);
            }
            if (phy.solidCount > 100) {
                result.warnings.add("Large solid count: " + phy.solidCount);
            }

        } catch (Exception e) {
            result.warnings.add("[PHY] Parse error: " + e.getMessage());
        }
    }

    private static void checkChecksumConsistency(ModelGroup group, DiagnosticResult result) {
        if (result.checksums.size() < 2) return;

        Set<Integer> unique = new HashSet<>(result.checksums.values());
        if (unique.size() <= 1) {
            result.infoFields.add("Checksums: ALL MATCH (0x" + String.format("%08X", unique.iterator().next()) + ")");
            return;
        }

        result.warnings.add("Checksums don't match!");
        Integer mdlCs = result.checksums.get("MDL");
        for (Map.Entry<String, Integer> e : result.checksums.entrySet()) {
            if (mdlCs != null && !"MDL".equals(e.getKey()) && !e.getValue().equals(mdlCs)) {
                result.warnings.add("  " + e.getKey() + " (0x" + String.format("%08X", e.getValue()) + ") != MDL (0x" + String.format("%08X", mdlCs) + ")");
            }
        }
    }

    // ==================== TEXTURE DIAGNOSTICS ====================

    /**
     * Check if a model's textures can be resolved by scanning VMT/VTF files
     * in the associated materials directories.
     *
     * @param packageDir The model package directory
     * @return A diagnostic message describing the texture resolution status
     */
    public static String diagnoseTextures(Path packageDir) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  TEXTURE DIAGNOSTICS for ").append(packageDir.getFileName()).append("\n");

        try {
            List<Path> materialsDirs = ModelLoadManager.findAllMaterialsDirs(packageDir);
            if (materialsDirs.isEmpty()) {
                sb.append("  WARNING: No materials directories found!\n");
                sb.append("  Expected a 'materials/' folder near the model package.\n");
                return sb.toString();
            }

            sb.append("  Materials directories (").append(materialsDirs.size()).append(" found):\n");
            for (Path md : materialsDirs) {
                sb.append("    - ").append(md).append("\n");
            }

            int vmtCount = 0;
            int vtfCount = 0;
            int otherCount = 0;
            long totalVtfSize = 0;

            for (Path matDir : materialsDirs) {
                if (!Files.exists(matDir)) continue;
                try (Stream<Path> walk = Files.walk(matDir, 8)) {
                    List<Path> files = walk.filter(Files::isRegularFile).toList();
                    for (Path f : files) {
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".vmt")) vmtCount++;
                        else if (name.endsWith(".vtf")) {
                            vtfCount++;
                            try { totalVtfSize += Files.size(f); } catch (IOException ignored) {}
                        } else if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                            otherCount++;
                        }
                    }
                }
            }

            sb.append("  Material files: ").append(vmtCount).append(" VMT, ")
                .append(vtfCount).append(" VTF (")
                .append(String.format("%.1f", totalVtfSize / (1024.0 * 1024.0)))
                .append(" MB), ").append(otherCount).append(" PNG/JPG\n");

            // Try to find the MDL and check its texture references
            Path mdlFile = findMdlFile(packageDir);
            if (mdlFile != null && vmtCount > 0) {
                try {
                    byte[] mdlData = Files.readAllBytes(mdlFile);
                    MdlDataTypes.ParsedModel mdl = MdlParser.parse(mdlData);
                    sb.append("  MDL texture references: ").append(mdl.textures.size()).append("\n");
                    for (int i = 0; i < mdl.textures.size(); i++) {
                        String texName = mdl.textures.get(i).name;
                        boolean found = false;
                        for (Path matDir : materialsDirs) {
                            Path candidateVmt = matDir.resolve(texName + ".vmt");
                            Path candidateVtf = matDir.resolve(texName + ".vtf");
                            if (Files.exists(candidateVmt) || Files.exists(candidateVtf)) {
                                found = true;
                                break;
                            }
                            // Also check with cdtexture prefixes
                            for (String cdTex : mdl.cdTextures) {
                                String cdPrefix = cdTex.replace('\\', '/');
                                if (!cdPrefix.endsWith("/")) cdPrefix += "/";
                                candidateVmt = matDir.resolve(cdPrefix + texName + ".vmt");
                                candidateVtf = matDir.resolve(cdPrefix + texName + ".vtf");
                                if (Files.exists(candidateVmt) || Files.exists(candidateVtf)) {
                                    found = true;
                                    break;
                                }
                            }
                            if (found) break;
                        }
                        sb.append("    Texture[").append(i).append("]: '").append(texName)
                            .append("' -> ").append(found ? "FOUND" : "MISSING").append("\n");
                    }
                } catch (Exception e) {
                    sb.append("  MDL parse error for texture check: ").append(e.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("  Error during texture diagnostics: ").append(e.getMessage()).append("\n");
        }

        return sb.toString();
    }

    private static Path findMdlFile(Path packageDir) {
        try (Stream<Path> files = Files.walk(packageDir, 4)) {
            return files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".mdl"))
                .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public static String formatResults(List<DiagnosticResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(70)).append("\n");
        sb.append("  MODEL DIAGNOSTICS (").append(java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append(")\n");
        sb.append("=".repeat(70)).append("\n\n");

        sb.append("  Found ").append(results.size()).append(" model groups:\n\n");
        for (DiagnosticResult r : results) {
            String status = r.complete ? "COMPLETE" : "INCOMPLETE";
            String phyStatus = r.hasPhy ? "has PHY" : "no PHY";
            sb.append("  [").append(status).append("] ").append(r.modelName).append(" (").append(phyStatus).append(")\n");
        }

        sb.append("\n").append("=".repeat(70)).append("\n");
        sb.append("  DETAILED ANALYSIS").append("\n");
        sb.append("=".repeat(70)).append("\n\n");

        for (DiagnosticResult r : results) {
            sb.append("_".repeat(60)).append("\n");
            sb.append("  GROUP: ").append(r.modelName).append("\n");
            sb.append("_".repeat(60)).append("\n");

            for (String w : r.warnings) {
                sb.append("  WARNING: ").append(w).append("\n");
            }
            for (String i : r.infoFields) {
                sb.append("  ").append(i).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}