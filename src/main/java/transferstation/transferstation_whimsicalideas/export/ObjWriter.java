package transferstation.transferstation_whimsicalideas.export;

import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ObjWriter {

    public static void write(SourceModelData model, List<ModelExporter.TextureEntry> textures, Path outputDir) throws IOException {
        String modelName = sanitizeName(model.name.isEmpty() ? "model" : model.name);
        Path objFile = outputDir.resolve(modelName + ".obj");
        Path mtlFile = outputDir.resolve(modelName + ".mtl");

        try (BufferedWriter obj = Files.newBufferedWriter(objFile);
             BufferedWriter mtl = Files.newBufferedWriter(mtlFile)) {

            mtl.write("# Material file for " + modelName + "\n");

            obj.write("# Wavefront OBJ exported from TransferStation WhimsicalIdeas\n");
            obj.write("mtllib " + modelName + ".mtl\n");

            int globalVOffset = 0;
            Set<String> writtenMaterials = new HashSet<>();

            for (int m = 0; m < model.meshes.size(); m++) {
                SourceModelData.MeshData mesh = model.meshes.get(m);
                float[] verts = mesh.vertices;
                if (verts == null || verts.length < 8) continue;

                String matName = "mesh_" + m;
                if (mesh.texture != null) {
                    String path = mesh.texture.getPath();
                    if (path.contains("/")) path = path.substring(path.lastIndexOf('/') + 1);
                    if (path.contains(".")) path = path.substring(0, path.lastIndexOf('.'));
                    matName = path;
                }
                matName = sanitizeName(matName);

                obj.write("g " + matName + "\n");
                obj.write("usemtl " + matName + "\n");

                for (int i = 0; i < verts.length; i += 8) {
                    obj.write(String.format("v %.6f %.6f %.6f\n", verts[i], verts[i + 1], verts[i + 2]));
                    obj.write(String.format("vn %.6f %.6f %.6f\n", verts[i + 3], verts[i + 4], verts[i + 5]));
                    obj.write(String.format("vt %.6f %.6f\n", verts[i + 6], verts[i + 7]));
                }

                int[] indices = mesh.indices;
                for (int i = 0; i < indices.length; i += 3) {
                    int v1 = globalVOffset + indices[i] + 1;
                    int v2 = globalVOffset + indices[i + 1] + 1;
                    int v3 = globalVOffset + indices[i + 2] + 1;
                    obj.write(String.format("f %d/%d/%d %d/%d/%d %d/%d/%d\n",
                        v1, v1, v1, v2, v2, v2, v3, v3, v3));
                }

                globalVOffset += verts.length / 8;

                if (writtenMaterials.add(matName)) {
                    mtl.write("newmtl " + matName + "\n");
                    mtl.write("Ka 0.6 0.6 0.6\n");
                    mtl.write("Kd 0.8 0.8 0.8\n");
                    mtl.write("Ks 0.1 0.1 0.1\n");
                    mtl.write("Ns 32.0\n");
                    String matchedPng = findMatchingTexture(matName, textures);
                    if (matchedPng != null) {
                        mtl.write("map_Kd textures/" + matchedPng + "\n");
                    }
                    if (mesh.alpha < 1.0f) {
                        mtl.write(String.format("d %.2f\n", mesh.alpha));
                    }
                    mtl.write("\n");
                }
            }
        }
    }

    private static String findMatchingTexture(String matName, List<ModelExporter.TextureEntry> textures) {
        String matLower = matName.toLowerCase();
        for (ModelExporter.TextureEntry tex : textures) {
            String tName = tex.name();
            if (tName.contains("/")) tName = tName.substring(tName.lastIndexOf('/') + 1);
            if (tName.contains(".")) tName = tName.substring(0, tName.lastIndexOf('.'));
            if (tName.equalsIgnoreCase(matName) || matLower.contains(tName.toLowerCase())) {
                return tex.name();
            }
        }
        return null;
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
