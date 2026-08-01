package transferstation.transferstation_whimsicalideas.client.model;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class VpkParser {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final int VPK_SIGNATURE = 0x55AA1234;
    public static final int ARCHIVE_INDEX_EMBEDDED = 0x7FFF;

    public static class VpkHeader {
        public int signature;
        public int version;
        public int treeSize;

        // v2 only
        public int fileDataSectionSize;
        public int archiveMD5SectionSize;
        public int otherMD5SectionSize;
        public int signatureSectionSize;
    }

    public static class VpkEntry {
        public String extension;
        public String path;
        public String filename;
        public String fullPath;

        public int crc32;
        public int preloadBytes;
        public int archiveIndex;
        public int offset;
        public int length;
        public byte[] preloadData;

        public boolean isEmbedded() {
            return archiveIndex == ARCHIVE_INDEX_EMBEDDED;
        }

        public long endOffset() {
            return (long) offset + length;
        }
    }

    public static class VpkArchive {
        public final Path dirFile;
        public final VpkHeader header;
        public final List<VpkEntry> entries = new ArrayList<>();
        public final Map<String, VpkEntry> entryMap = new LinkedHashMap<>();

        private final byte[] dirData;
        private final Map<Integer, Path> archiveFiles = new HashMap<>();

        VpkArchive(Path dirFile, VpkHeader header, byte[] dirData) {
            this.dirFile = dirFile;
            this.header = header;
            this.dirData = dirData;
        }

        public VpkEntry getEntry(String fullPath) {
            String norm = fullPath.replace('\\', '/').toLowerCase(Locale.ROOT);
            return entryMap.get(norm);
        }

        public boolean contains(String fullPath) {
            return getEntry(fullPath) != null;
        }

        public List<VpkEntry> listEntries(String extension) {
            String ext = extension.startsWith(".") ? extension.substring(1).toLowerCase() : extension.toLowerCase();
            List<VpkEntry> result = new ArrayList<>();
            for (VpkEntry entry : entries) {
                if (entry.extension.equals(ext)) {
                    result.add(entry);
                }
            }
            return result;
        }

        public List<VpkEntry> listEntriesByDir(String directory) {
            String dir = directory.replace('\\', '/').toLowerCase(Locale.ROOT);
            if (!dir.endsWith("/")) dir += "/";
            List<VpkEntry> result = new ArrayList<>();
            for (VpkEntry entry : entries) {
                String entryPath = entry.path.toLowerCase(Locale.ROOT);
                if (!entryPath.isEmpty()) entryPath += "/";
                if (entryPath.startsWith(dir)) {
                    result.add(entry);
                }
            }
            return result;
        }

        public byte[] readEntry(VpkEntry entry) throws IOException {
            int totalSize = entry.length + entry.preloadBytes;
            if (totalSize <= 0) return new byte[0];
            ByteBuffer data = ByteBuffer.allocate(totalSize);
            data.order(ByteOrder.LITTLE_ENDIAN);

            if (entry.preloadBytes > 0 && entry.preloadData != null) {
                data.put(entry.preloadData);
            }

            if (entry.length > 0) {
                if (entry.isEmbedded()) {
                    int embedOffset = getEmbeddedDataOffset();
                    int start = embedOffset + entry.offset;
                    int end = start + entry.length;
                    // Bounds-check before copying: a corrupt/malformed entry with
                    // offset+length exceeding the file would throw IndexOutOfBounds and
                    // kill the whole model load. Skip the entry's data instead.
                    if (start < 0 || end > dirData.length) {
                        LOGGER.warn("[VpkParser] Embedded entry out of bounds: offset={} len={} fileSize={}",
                                start, entry.length, dirData.length);
                    } else {
                        data.put(dirData, start, entry.length);
                    }
                } else {
                    Path archiveFile = getArchiveFile(entry.archiveIndex);
                    if (archiveFile == null) {
                        throw new IOException("Archive file not found for index " + entry.archiveIndex);
                    }
                    try (RandomAccessFile raf = new RandomAccessFile(archiveFile.toFile(), "r");
                         FileChannel ch = raf.getChannel()) {
                        ByteBuffer chunk = ByteBuffer.allocate(entry.length);
                        chunk.order(ByteOrder.LITTLE_ENDIAN);
                        ch.position(entry.offset);
                        // FileChannel.read is not guaranteed to fill the buffer in one
                        // call; loop until the whole entry is read or the channel ends.
                        int got = 0;
                        while (got < entry.length) {
                            int n = ch.read(chunk);
                            if (n < 0) break;
                            got += n;
                        }
                        chunk.flip();
                        data.put(chunk);
                    }
                }
            }

            data.flip();
            byte[] result = new byte[data.remaining()];
            data.get(result);
            return result;
        }

        private int getEmbeddedDataOffset() {
            if (header.version == 1) {
                return 12 + header.treeSize;
            } else {
                return 28 + header.treeSize;
            }
        }

        private Path getArchiveFile(int index) {
            return archiveFiles.computeIfAbsent(index, idx -> {
                String baseName = dirFile.getFileName().toString();
                if (baseName.endsWith("_dir.vpk")) {
                    baseName = baseName.substring(0, baseName.length() - 8);
                } else if (baseName.endsWith(".vpk")) {
                    baseName = baseName.substring(0, baseName.length() - 4);
                }
                String archiveName = baseName + "_" + String.format("%03d", idx) + ".vpk";
                Path sibling = dirFile.resolveSibling(archiveName);
                if (Files.exists(sibling)) return sibling;

                for (int fallback = 0; fallback <= idx; fallback++) {
                    Path alt = dirFile.resolveSibling(baseName + "_" + String.format("%03d", fallback) + ".vpk");
                    if (Files.exists(alt)) return alt;
                }
                return null;
            });
        }

        public void close() {
            archiveFiles.clear();
        }
    }

    public static VpkArchive open(Path dirFile) throws IOException {
        if (!Files.exists(dirFile)) {
            throw new IOException("VPK directory file not found: " + dirFile);
        }

        byte[] fileData;
        try (FileChannel ch = FileChannel.open(dirFile, StandardOpenOption.READ)) {
            long size = ch.size();
            if (size > 512 * 1024 * 1024) {
                throw new IOException("VPK file too large: " + size + " bytes");
            }
            fileData = new byte[(int) size];
            // FileChannel.read is not guaranteed to fill the buffer in one call, so loop
            // until the whole file is read. A partial read leaves trailing zeros that the
            // directory-tree parser would treat as garbage VPK entries.
            int read = 0;
            while (read < fileData.length) {
                int n = ch.read(ByteBuffer.wrap(fileData, read, fileData.length - read));
                if (n < 0) break;
                read += n;
            }
        }

        ByteBuffer buf = ByteBuffer.wrap(fileData);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        VpkHeader header = parseHeader(buf, fileData.length);

        int treeOffset;
        if (header.signature == VPK_SIGNATURE) {
            treeOffset = header.version == 1 ? 12 : 28;
        } else {
            treeOffset = 0;
        }

        int treeEnd = treeOffset + header.treeSize;
        if (treeEnd > fileData.length) {
            throw new IOException("VPK tree extends beyond file: treeEnd=" + treeEnd + " fileSize=" + fileData.length);
        }

        byte[] treeData = Arrays.copyOfRange(fileData, treeOffset, treeEnd);
        VpkArchive archive = new VpkArchive(dirFile, header, fileData);

        parseDirectoryTree(treeData, archive);

        LOGGER.info("[VpkParser] Opened VPK v{}: {} with {} entries (treeSize={})",
            header.version, dirFile.getFileName(), archive.entries.size(), header.treeSize);

        return archive;
    }

    private static VpkHeader parseHeader(ByteBuffer buf, int fileSize) {
        VpkHeader h = new VpkHeader();
        h.signature = buf.getInt(0);

        if (h.signature != VPK_SIGNATURE) {
            h.version = 1;
            h.treeSize = fileSize;
            h.fileDataSectionSize = 0;
            h.archiveMD5SectionSize = 0;
            h.otherMD5SectionSize = 0;
            h.signatureSectionSize = 0;
            return h;
        }

        h.version = buf.getInt(4);
        h.treeSize = buf.getInt(8);

        if (h.version == 2 && fileSize >= 28) {
            h.fileDataSectionSize = buf.getInt(12);
            h.archiveMD5SectionSize = buf.getInt(16);
            h.otherMD5SectionSize = buf.getInt(20);
            h.signatureSectionSize = buf.getInt(24);
        } else {
            h.fileDataSectionSize = 0;
            h.archiveMD5SectionSize = 0;
            h.otherMD5SectionSize = 0;
            h.signatureSectionSize = 0;
        }

        return h;
    }

    private static void parseDirectoryTree(byte[] treeData, VpkArchive archive) {
        int pos = 0;

        while (pos < treeData.length) {
            String extension = readNullString(treeData, pos);
            pos += extension.length() + 1;
            if (extension.isEmpty()) break;

            while (pos < treeData.length) {
                String path = readNullString(treeData, pos);
                pos += path.length() + 1;
                if (path.isEmpty()) break;

                while (pos < treeData.length) {
                    String filename = readNullString(treeData, pos);
                    pos += filename.length() + 1;
                    if (filename.isEmpty()) break;

                    if (pos + 18 > treeData.length) break;

                    VpkEntry entry = new VpkEntry();
                    entry.extension = extension;
                    entry.path = path;
                    entry.filename = filename;

                    String fullPathBuilder = path + '/' +
                            filename +
                            '.' + extension;
                    entry.fullPath = fullPathBuilder.replace('\\', '/').toLowerCase(Locale.ROOT);

                    entry.crc32 = readIntLE(treeData, pos);
                    entry.preloadBytes = readShortLE(treeData, pos + 4) & 0xFFFF;
                    entry.archiveIndex = readShortLE(treeData, pos + 6) & 0xFFFF;
                    entry.offset = readIntLE(treeData, pos + 8);
                    entry.length = readIntLE(treeData, pos + 12);
                    pos += 18;

                    if (entry.preloadBytes > 0 && pos + entry.preloadBytes <= treeData.length) {
                        entry.preloadData = Arrays.copyOfRange(treeData, pos, pos + entry.preloadBytes);
                        pos += entry.preloadBytes;
                    }

                    archive.entries.add(entry);
                    archive.entryMap.put(entry.fullPath, entry);
                }
            }
        }
    }

    public static List<String> findVpkFiles(Path searchDir) {
        List<String> vpkFiles = new ArrayList<>();
        if (!Files.exists(searchDir)) return vpkFiles;

        try (var files = Files.walk(searchDir, 3)) {
            files.filter(Files::isRegularFile)
                .filter(f -> f.getFileName().toString().toLowerCase().endsWith("_dir.vpk"))
                .forEach(f -> vpkFiles.add(f.toAbsolutePath().toString()));
        } catch (IOException e) {
            LOGGER.warn("[VpkParser] Failed to scan for VPK files in {}", searchDir, e);
        }

        return vpkFiles;
    }

    public static Set<String> listModelPaths(VpkArchive archive) {
        Set<String> modelDirs = new LinkedHashSet<>();
        for (VpkEntry entry : archive.entries) {
            if (entry.extension.equals("mdl") || entry.extension.equals("vvd") ||
                entry.extension.equals("vtx") || entry.extension.equals("smd")) {
                String dir = entry.path;
                if (!dir.isEmpty()) {
                    modelDirs.add(dir);
                } else {
                    modelDirs.add("/");
                }
            }
        }
        return modelDirs;
    }

    public static Map<String, Set<String>> groupModelFiles(VpkArchive archive, String modelDir) {
        Map<String, Set<String>> modelSets = new LinkedHashMap<>();
        String prefix = modelDir.isEmpty() || modelDir.equals("/") ? "" : modelDir + "/";
        String prefixLower = prefix.toLowerCase(Locale.ROOT);

        for (VpkEntry entry : archive.entries) {
            if (entry.path.isEmpty() && !modelDir.isEmpty() && !modelDir.equals("/")) continue;
            if (!modelDir.isEmpty() && !modelDir.equals("/")) {
                String entryPath = entry.path.toLowerCase(Locale.ROOT);
                if (!entryPath.equals(modelDir.toLowerCase(Locale.ROOT)) &&
                    !entryPath.startsWith(modelDir.toLowerCase(Locale.ROOT) + "/")) {
                    continue;
                }
            }

            String name = entry.filename;
            String ext = entry.extension;

            if (ext.equals("mdl") || ext.equals("vvd") || ext.equals("vtx") || ext.equals("smd")) {
                modelSets.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(ext);
            }
        }

        Map<String, Set<String>> complete = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : modelSets.entrySet()) {
            Set<String> exts = entry.getValue();
            boolean hasMdl = exts.contains("mdl");
            boolean hasVvd = exts.contains("vvd");
            boolean hasVtx = exts.contains("vtx");

            if (hasMdl || hasVvd || hasVtx || exts.contains("smd")) {
                complete.put(entry.getKey(), exts);
            }
        }

        return complete;
    }

    /**
     * Container for model file data read directly from a VPK archive.
     */
    public static class VpkModelFiles {
        public byte[] mdlData;
        public byte[] vvdData;
        public byte[] vtxData;
        public byte[] phyData;
        public byte[] smdData;

        public boolean hasMdlTrio() {
            return mdlData != null && vvdData != null && vtxData != null;
        }

        public boolean hasSmd() {
            return smdData != null;
        }

        public boolean isValid() {
            return hasMdlTrio() || hasSmd();
        }
    }

    /**
     * Read model file data (MDL/VVD/VTX/PHY/SMD) directly from a VPK archive.
     * This avoids extracting files to disk before loading.
     *
     * @param archive  The opened VPK archive
     * @param modelDir The model directory within the VPK (e.g. "models/player")
     * @return VpkModelFiles containing the file data, or null if no valid model found
     */
    public static VpkModelFiles readModelFiles(VpkArchive archive, String modelDir) throws IOException {
        String dirNorm = modelDir.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (dirNorm.startsWith("/")) dirNorm = dirNorm.substring(1);
        if (dirNorm.endsWith("/")) dirNorm = dirNorm.substring(0, dirNorm.length() - 1);

        VpkModelFiles files = new VpkModelFiles();

        for (VpkEntry entry : archive.entries) {
            String entryDir = entry.path.toLowerCase(Locale.ROOT);
            if (!entryDir.equals(dirNorm)) continue;

            String entryName = entry.filename;
            String ext = entry.extension;

            switch (ext) {
                case "mdl" -> files.mdlData = archive.readEntry(entry);
                case "vvd" -> files.vvdData = archive.readEntry(entry);
                case "vtx" -> {
                    // Source VPK entries: player.dx90.vtx → filename="player.dx90", ext="vtx"
                    // Prefer DX90 variant over generic vtx or DX80/SW variants
                    boolean isDx90 = entryName.endsWith(".dx90");
                    boolean isSwOrDx80 = entryName.endsWith(".sw") || entryName.endsWith(".dx80");
                    if (isDx90) {
                        files.vtxData = archive.readEntry(entry);
                    } else if (files.vtxData == null && !isSwOrDx80) {
                        files.vtxData = archive.readEntry(entry);
                    }
                }
                case "phy" -> files.phyData = archive.readEntry(entry);
                case "smd" -> files.smdData = archive.readEntry(entry);
            }
        }

        return files.isValid() ? files : null;
    }

    public static void extractModelFromVpk(VpkArchive archive, String modelName,
                                            Path outputDir, boolean overwrite) throws IOException {
        String basePath = modelName + "/";
        Files.createDirectories(outputDir);

        for (VpkEntry entry : archive.entries) {
            String entryPath = entry.path.toLowerCase(Locale.ROOT);
            if (!entryPath.equals(modelName.toLowerCase(Locale.ROOT)) &&
                !entryPath.startsWith(basePath.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (!entry.extension.equals("mdl") && !entry.extension.equals("vvd") &&
                !entry.extension.equals("vtx") && !entry.extension.equals("smd") &&
                !entry.extension.equals("vmt") && !entry.extension.equals("vtf") &&
                !entry.extension.equals("phy")) {
                continue;
            }

            String relPath;
            if (entry.path.isEmpty()) {
                relPath = entry.filename;
            } else {
                relPath = entry.path + "/" + entry.filename;
            }
            relPath += "." + entry.extension;

            Path targetFile = outputDir.resolve(relPath).normalize();
            if (!targetFile.startsWith(outputDir.normalize())) {
                LOGGER.warn("[VpkParser] Skipping path traversal attempt: {}", relPath);
                continue;
            }

            if (!overwrite && Files.exists(targetFile)) continue;

            Files.createDirectories(targetFile.getParent());

            byte[] data = archive.readEntry(entry);
            Files.write(targetFile, data);
        }
    }

    private static String readNullString(byte[] data, int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1);
    }

    private static int readIntLE(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8) |
            ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    private static int readShortLE(byte[] data, int off) {
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8);
    }
}
