package transferstation.transferstation_whimsicalideas.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import transferstation.transferstation_whimsicalideas.client.model.SourceModelData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObjWriterTest {

    @TempDir
    Path tempDir;

    private SourceModelData createSingleMeshModel() {
        SourceModelData model = new SourceModelData();
        model.name = "test_cube";
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{
                0, 0, 0,  0, 0, 1,  0, 0,
                1, 0, 0,  0, 0, 1,  1, 0,
                1, 1, 0,  0, 0, 1,  1, 1,
                0, 1, 0,  0, 0, 1,  0, 1
            })
            .indices(new int[]{0, 1, 2, 0, 2, 3})
            .build());
        return model;
    }

    @Test
    void writesObjFile() throws IOException {
        SourceModelData model = createSingleMeshModel();
        ObjWriter.write(model, List.of(), tempDir);

        Path objFile = tempDir.resolve("test_cube.obj");
        Path mtlFile = tempDir.resolve("test_cube.mtl");

        assertTrue(Files.exists(objFile));
        assertTrue(Files.exists(mtlFile));
    }

    @Test
    void objContainsExpectedSections() throws IOException {
        SourceModelData model = createSingleMeshModel();
        ObjWriter.write(model, List.of(), tempDir);

        String objContent = Files.readString(tempDir.resolve("test_cube.obj"));

        assertTrue(objContent.contains("mtllib test_cube.mtl"));
        assertTrue(objContent.contains("v "));
        assertTrue(objContent.contains("vn "));
        assertTrue(objContent.contains("vt "));
        assertTrue(objContent.contains("f "));
        assertTrue(objContent.contains("usemtl "));
    }

    @Test
    void objVertexCount() throws IOException {
        SourceModelData model = createSingleMeshModel();
        ObjWriter.write(model, List.of(), tempDir);

        long vertexLines = Files.lines(tempDir.resolve("test_cube.obj"))
            .filter(l -> l.startsWith("v ")).count();
        assertEquals(4, vertexLines);
    }

    @Test
    void mtlContainsDefaults() throws IOException {
        SourceModelData model = createSingleMeshModel();
        ObjWriter.write(model, List.of(), tempDir);

        String mtlContent = Files.readString(tempDir.resolve("test_cube.mtl"));
        assertTrue(mtlContent.contains("newmtl"));
        assertTrue(mtlContent.contains("Ka 0.6"));
        assertTrue(mtlContent.contains("Kd 0.8"));
    }

    @Test
    void exportWithTexture() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "tex_export";
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 0,1,0, 0,0,1, 0,1})
            .indices(new int[]{0,1,2})
            .build());
        Path pngPath = tempDir.resolve("mesh_0.png");
        Files.writeString(pngPath, "fake-png");
        List<ModelExporter.TextureEntry> textures = List.of(
            new ModelExporter.TextureEntry("mesh_0.png", pngPath)
        );

        ObjWriter.write(model, textures, tempDir);
        String mtlContent = Files.readString(tempDir.resolve("tex_export.mtl"));
        assertTrue(mtlContent.contains("map_Kd textures/mesh_0.png"));
    }

    @Test
    void emptyMeshProducesNoGeometry() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "empty";
        ObjWriter.write(model, List.of(), tempDir);
        String content = Files.readString(tempDir.resolve("empty.obj"));
        assertFalse(content.contains("v "));
    }

    @Test
    void sanitizesModelName() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "bad/name:test";
        ObjWriter.write(model, List.of(), tempDir);
        assertTrue(Files.exists(tempDir.resolve("bad_name_test.obj")));
    }

    @Test
    void multipleMeshes() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "multi";
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 0,1,0, 0,0,1, 0,1})
            .indices(new int[]{0,1,2})
            .build());
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 0,1,0, 0,0,1, 0,1})
            .indices(new int[]{0,1,2})
            .build());
        ObjWriter.write(model, List.of(), tempDir);
        String content = Files.readString(tempDir.resolve("multi.obj"));
        assertEquals(6, content.split("v ").length - 1);
    }

    @Test
    void textureFindMatching() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "tex_test";
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 0,1,0, 0,0,1, 0,1})
            .indices(new int[]{0,1,2})
            .build());
        Path pngPath = tempDir.resolve("mesh.png");
        Files.writeString(pngPath, "data");
        ObjWriter.write(model, List.of(
            new ModelExporter.TextureEntry("mesh.png", pngPath)
        ), tempDir);
        String mtl = Files.readString(tempDir.resolve("tex_test.mtl"));
        String texEntry = mtl.lines().filter(l -> l.contains("map_Kd")).findFirst().orElse("");
        assertTrue(texEntry.contains("mesh.png"), "Expected map_Kd line to contain texture name, got: " + texEntry);
    }

    @Test
    void noTextureMatchSkipsMapKd() throws IOException {
        SourceModelData model = new SourceModelData();
        model.name = "no_match";
        model.meshes.add(new SourceModelData.MeshData.Builder()
            .vertices(new float[]{0,0,0, 0,0,1, 0,0, 1,0,0, 0,0,1, 1,0, 0,1,0, 0,0,1, 0,1})
            .indices(new int[]{0,1,2})
            .build());
        Path pngPath = tempDir.resolve("unrelated.png");
        Files.writeString(pngPath, "data");
        ObjWriter.write(model, List.of(
            new ModelExporter.TextureEntry("unrelated.png", pngPath)
        ), tempDir);
        String mtl = Files.readString(tempDir.resolve("no_match.mtl"));
        assertFalse(mtl.contains("map_Kd"));
    }
}
