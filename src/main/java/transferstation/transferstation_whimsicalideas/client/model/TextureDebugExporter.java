package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Debug utility that exports all of a model package's GMod textures (VTF, plus
 * PNG/JPG fallbacks) to PNG files on disk so developers can inspect them.
 *
 * Self-contained: only uses java.awt.image, java.io, java.nio.file,
 * javax.imageio, java.util and org.slf4j. No Minecraft client classes required.
 */
public final class TextureDebugExporter {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TextureDebugExporter() {
    }

    /**
     * Export all textures for the given model package directory into outputDir.
     *
     * @param packageDir the model package directory (e.g. models/player/soldier)
     * @param outputDir  the directory where exported PNGs will be written
     * @return the number of PNGs successfully written (VTF-decoded + copied),
     *         or -1 if the package directory is invalid or output dir cannot be created
     */
    public static int exportModelTextures(Path packageDir, Path outputDir) {
        if (packageDir == null || !Files.exists(packageDir) || !Files.isDirectory(packageDir)) {
            LOGGER.warn("Texture export skipped: package directory does not exist or is not a directory: {}", packageDir);
            return -1;
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            LOGGER.error("Texture export failed: could not create output directory {}", outputDir, e);
            return -1;
        }

        List<Path> materialsDirs = ModelLoadManager.findAllMaterialsDirs(packageDir);

        int decoded = 0;
        int copied = 0;
        int failed = 0;

        for (Path materialsDir : materialsDirs) {
            if (!Files.exists(materialsDir) || !Files.isDirectory(materialsDir)) {
                continue;
            }

            try {
                List<Path> files = Files.walk(materialsDir, 8).toList();
                for (Path f : files) {
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }

                    String name = f.getFileName().toString();
                    String lower = name.toLowerCase();

                    if (lower.endsWith(".vtf")) {
                        try {
                            VtfParser.VtfImageData result = VtfParser.parse(Files.readAllBytes(f));
                            if (result != null && result.image != null) {
                                String relPath = sanitizeRelPath(materialsDir.relativize(f).toString())
                                        .replaceAll("\\.vtf$", "");
                                Path target = outputDir.resolve(relPath + ".png");
                                if (exportBufferedImage(result.image, target)) {
                                    decoded++;
                                } else {
                                    failed++;
                                }
                            } else {
                                LOGGER.debug("VTF parse returned no image for {}", f);
                                failed++;
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Failed to parse VTF {}: {}", f, e.getMessage());
                            failed++;
                        }
                    } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                        try {
                            String relPath = sanitizeRelPath(materialsDir.relativize(f).toString());
                            Path target = outputDir.resolve(relPath);
                            Files.createDirectories(target.getParent());
                            Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            copied++;
                        } catch (IOException e) {
                            LOGGER.debug("Failed to copy image {}: {}", f, e.getMessage());
                            failed++;
                        }
                    }
                    // .vmt and everything else is skipped
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to walk materials dir {}: {}", materialsDir, e.getMessage());
            }
        }

        int total = decoded + copied;
        LOGGER.info("Texture export complete for {}: decoded VTF={}, copied common images={}, failed={} -> {}",
                packageDir, decoded, copied, failed, outputDir);
        return total;
    }

    /**
     * Convenience overload that writes into {@code packageDir/debug_textures}.
     */
    public static int exportModelTextures(Path packageDir) {
        return exportModelTextures(packageDir, packageDir.resolve("debug_textures"));
    }

    /**
     * Write a single BufferedImage as a PNG file, creating parent directories as needed.
     *
     * @return true on success, false on failure
     */
    public static boolean exportBufferedImage(BufferedImage image, Path targetFile) {
        if (image == null) {
            return false;
        }
        try {
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return ImageIO.write(image, "png", targetFile.toFile());
        } catch (IOException e) {
            LOGGER.debug("Failed to write PNG {}: {}", targetFile, e.getMessage());
            return false;
        }
    }

    /**
     * Normalize a relative path string so it can be used as a sub-path of outputDir.
     * Backslashes become forward slashes (Path.resolve handles '/' on Windows fine),
     * and any illegal filename characters are replaced with '_'.
     */
    private static String sanitizeRelPath(String relPath) {
        if (relPath == null) {
            return "";
        }
        String normalized = relPath.replace('\\', '/');
        // Replace characters that are illegal in file names on common platforms.
        StringBuilder sb = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '/' || c == ':' || (c >= 32 && c != '<' && c != '>' && c != '"'
                    && c != '|' && c != '?' && c != '*')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
