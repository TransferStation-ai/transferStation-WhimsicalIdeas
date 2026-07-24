package transferstation.transferstation_whimsicalideas.export;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class TextureExporter {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static List<ModelExporter.TextureEntry> exportTextures(Path packageDir, Path outputDir) throws IOException {
        List<ModelExporter.TextureEntry> result = new ArrayList<>();
        Path texturesDir = outputDir.resolve("textures");
        Files.createDirectories(texturesDir);

        Path materialsDir = findMaterialsDir(packageDir);
        if (materialsDir == null || !Files.exists(materialsDir)) {
            LOGGER.warn("No materials directory found for {}", packageDir);
            return result;
        }

        try (Stream<Path> walk = Files.walk(materialsDir, 8)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString().toLowerCase();
                if (!name.endsWith(".vtf")) continue;

                try {
                    byte[] data = Files.readAllBytes(f);
                    var vtf = VtfParser.parse(data);
                    if (vtf == null || vtf.image == null) continue;

                    String relPath = materialsDir.relativize(f).toString()
                        .replace('\\', '/')
                        .replaceAll("\\.vtf$", ".png");
                    Path target = texturesDir.resolve(relPath);
                    Files.createDirectories(target.getParent());

                    if (ImageIO.write(vtf.image, "png", target.toFile())) {
                        result.add(new ModelExporter.TextureEntry(relPath, target));
                    }
                } catch (Exception e) {
                    LOGGER.debug("Failed to export texture {}: {}", f, e.getMessage());
                }
            }
        }
        return result;
    }

    static Path findMaterialsDir(Path packageDir) {
        Path direct = packageDir.resolve("materials");
        if (Files.exists(direct) && Files.isDirectory(direct)) return direct;
        Path parent = packageDir.getParent();
        while (parent != null) {
            Path candidate = parent.resolve("materials");
            if (Files.exists(candidate) && Files.isDirectory(candidate)) return candidate;
            parent = parent.getParent();
        }
        return null;
    }
}
