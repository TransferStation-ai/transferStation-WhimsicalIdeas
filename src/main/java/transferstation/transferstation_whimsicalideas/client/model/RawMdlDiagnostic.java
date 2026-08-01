package transferstation.transferstation_whimsicalideas.client.model;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Standalone raw-binary diagnostic for Source Engine MDL/VVD/VTX files.
 * Reads model files directly with ByteBuffer — NO Minecraft dependency.
 * Used to verify the MdlParser's output against actual file contents.
 */
public class RawMdlDiagnostic {

    static final Charset ASCII = StandardCharsets.US_ASCII;
    static final Charset CP932 = Charset.forName("Shift_JIS");
    static final Charset CP1252 = Charset.forName("Windows-1252");

    public static void main(String[] args) throws Exception {
        String mdlPath = null, vvdPath = null, vtxPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mdl" -> mdlPath = args[++i];
                case "--vvd" -> vvdPath = args[++i];
                case "--vtx" -> vtxPath = args[++i];
            }
        }
        if (mdlPath != null) dumpMdl(mdlPath);
        if (vvdPath != null) dumpVvd(vvdPath);
        if (vtxPath != null) dumpVtx(vtxPath);
    }

    static ByteBuffer load(String path) throws IOException {
        byte[] data = readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        return buf;
    }

    static byte[] readAllBytes(String path) throws IOException {
        try (var fis = new FileInputStream(path);
             var bos = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[65536];
            int n;
            while ((n = fis.read(tmp)) != -1) bos.write(tmp, 0, n);
            return bos.toByteArray();
        }
    }

    // ======================== MDL ========================

    static void dumpMdl(String path) throws IOException {
        ByteBuffer buf = load(path);
        int limit = buf.limit();
        System.out.println("========================================");
        System.out.println("MDL: " + path + " (" + limit + " bytes)");
        System.out.println("========================================");

        int id = buf.getInt(0);
        int ver = buf.getInt(4);
        System.out.println("id=0x" + Integer.toHexString(id) + " (expected 0x54534449=IDTS) ver=" + ver);

        if (id != 0x54534449) {
            System.out.println("ERROR: not a valid MDL file");
            return;
        }

        // Assert header size: parseHeader reads 396 bytes
        // But SDK studio.h says header is 408 bytes
        // Check what's in the bytes between 396 and 408
        System.out.println("\n--- Header tail (bytes 376-420): ---");
        for (int i = 376; i < Math.min(420, limit); i++) {
            System.out.printf("  %3d: 0x%02X (%d)%n", i, buf.get(i) & 0xFF, buf.get(i));
        }

        System.out.println("\n--- Header fields ---");
        String name = readFixedString(buf, 12);
        System.out.println("name='" + name + "'");
        System.out.println("dataLength=" + buf.getInt(76));
        float[] eye = readFloat3(buf);
        System.out.println("eyeposition=(" + eye[0] + "," + eye[1] + "," + eye[2] + ")");
        System.out.println("flags=0x" + Integer.toHexString(buf.getInt(152)));

        // These field offsets are from MdlParser (parseHeader)
        int numbones = buf.getInt(156);
        int boneindex = buf.getInt(160);
        int numhitboxsets = buf.getInt(172);
        int hitboxsetindex = buf.getInt(176);
        int numlocalanim = buf.getInt(180);
        int localanimindex = buf.getInt(184);
        int numlocalseq = buf.getInt(188);
        int localseqindex = buf.getInt(192);
        int numtextures = buf.getInt(204);
        int textureindex = buf.getInt(208);
        int numcdtextures = buf.getInt(212);
        int cdtextureindex = buf.getInt(216);
        int numskinref = buf.getInt(220);
        int numskinfamilies = buf.getInt(224);
        int skinindex = buf.getInt(228);
        int numbodyparts = buf.getInt(232);
        int bodypartindex = buf.getInt(236);
        int numattachments = buf.getInt(240);
        int attachmentindex = buf.getInt(244);
        int numflexdesc = buf.getInt(260);
        int flexdescindex = buf.getInt(264);
        int numflexcontrollers = buf.getInt(268);
        int flexcontrollerindex = buf.getInt(272);
        int numflexrules = buf.getInt(276);
        int flexruleindex = buf.getInt(280);
        int numikchains = buf.getInt(284);
        int ikchainindex = buf.getInt(288);
        int nummouths = buf.getInt(292);
        int mouthindex = buf.getInt(296);
        int surfacepropindex = buf.getInt(308);
        int keyvalueindex = buf.getInt(312);
        int keyvaluesize = buf.getInt(316);
        int numincludemodels = buf.getInt(336);
        int includemodelindex = buf.getInt(340);
        int studiohdr2index = buf.getInt(392);

        System.out.println("numbones=" + numbones + " boneindex=0x" + Integer.toHexString(boneindex));
        System.out.println("numhitboxsets=" + numhitboxsets + " hitboxsetindex=0x" + Integer.toHexString(hitboxsetindex));
        System.out.println("numlocalanim=" + numlocalanim + " localanimindex=0x" + Integer.toHexString(localanimindex));
        System.out.println("numlocalseq=" + numlocalseq + " localseqindex=0x" + Integer.toHexString(localseqindex));
        System.out.println("numtextures=" + numtextures + " textureindex=0x" + Integer.toHexString(textureindex));
        System.out.println("numcdtextures=" + numcdtextures + " cdtextureindex=0x" + Integer.toHexString(cdtextureindex));
        System.out.println("numskinref=" + numskinref + " numskinfamilies=" + numskinfamilies + " skinindex=0x" + Integer.toHexString(skinindex));
        System.out.println("numbodyparts=" + numbodyparts + " bodypartindex=0x" + Integer.toHexString(bodypartindex));
        System.out.println("numlocalattachments=" + numattachments + " attachmentindex=0x" + Integer.toHexString(attachmentindex));
        System.out.println("numflexdesc=" + numflexdesc + " flexdescindex=0x" + Integer.toHexString(flexdescindex));
        System.out.println("numikchains=" + numikchains + " ikchainindex=0x" + Integer.toHexString(ikchainindex));
        System.out.println("numincludemodels=" + numincludemodels + " includemodelindex=0x" + Integer.toHexString(includemodelindex));
        System.out.println("surfacepropindex=0x" + Integer.toHexString(surfacepropindex));
        System.out.println("keyvalueindex=0x" + Integer.toHexString(keyvalueindex) + " keyvaluesize=" + keyvaluesize);
        System.out.println("studiohdr2index=0x" + Integer.toHexString(studiohdr2index));

        // Check if there's data at studiohdr2index
        if (studiohdr2index > 0 && studiohdr2index + 48 <= limit) {
            int numSrcBoneTransforms = buf.getInt(studiohdr2index + 4);
            int srcBoneTransformIndex = buf.getInt(studiohdr2index + 8);
            System.out.println("  studiohdr2: numSrcBoneTransforms=" + numSrcBoneTransforms
                + " srcBoneTransformIndex=0x" + Integer.toHexString(srcBoneTransformIndex));
        }

        // ====== Body Parts & Models ======
        System.out.println("\n--- Body Parts ---");
        int bpSize = 16;
        long expectedModelsTotal = 0;
        for (int i = 0; i < numbodyparts; i++) {
            int off = bodypartindex + i * bpSize;
            if (off + bpSize > limit) break;
            int nameOff = buf.getInt(off);
            int numModels = buf.getInt(off + 4);
            int base = buf.getInt(off + 8);
            int modelIdx = buf.getInt(off + 12);
            String bpName = nameOff > 0 ? readNullTermStr(buf, off + nameOff, limit) : "";
            System.out.println("  [" + i + "] name='" + bpName + "' numModels=" + numModels
                + " base=" + base + " modelIdx=0x" + Integer.toHexString(modelIdx));
            expectedModelsTotal += numModels;

            int modelAddr = off + modelIdx;
            int modelSize = 148; // V49 on-disk model size
            for (int m = 0; m < numModels; m++) {
                int mOff = modelAddr + m * modelSize;
                if (mOff + 64 > limit) break;
                String mName = readFixedString(buf, mOff);
                int mType = buf.getInt(mOff + 64);
                float mBounds = buf.getFloat(mOff + 68);
                int mNumMeshes = buf.getInt(mOff + 72);
                int mMeshIdx = buf.getInt(mOff + 76);
                int mNumVerts = buf.getInt(mOff + 80);
                int mVertIdx = buf.getInt(mOff + 84);
                int mTangentIdx = buf.getInt(mOff + 88);
                int mNumAttachments = buf.getInt(mOff + 92);
                int mAttachIdx = buf.getInt(mOff + 96);
                int mNumEyeballs = buf.getInt(mOff + 100);
                int mEyeballIdx = buf.getInt(mOff + 104);
                System.out.println("    model[" + m + "] name='" + mName + "' meshes=" + mNumMeshes
                    + " meshIdx=0x" + Integer.toHexString(mMeshIdx)
                    + " verts=" + mNumVerts + " vertIdx=0x" + Integer.toHexString(mVertIdx)
                    + " eyeballs=" + mNumEyeballs);
            }
        }
        System.out.println("  TOTAL models expected: " + expectedModelsTotal);

        // ====== Bones (first 50) ======
        System.out.println("\n--- Bones (first 50) ---");
        int boneSize = 216; // V49 bone size
        for (int i = 0; i < Math.min(numbones, 50); i++) {
            int off = boneindex + i * boneSize;
            if (off + boneSize > limit) break;
            int nameOff = buf.getInt(off);
            int parent = buf.getInt(off + 4);
            float px = buf.getFloat(off + 32);
            float py = buf.getFloat(off + 36);
            float pz = buf.getFloat(off + 40);
            String bName = nameOff > 0 ? readNullTermStr(buf, off + nameOff, limit) : "(indexed)";
            System.out.println("  [" + i + "] name='" + bName + "' parent=" + parent
                + " pos=(" + px + "," + py + "," + pz + ")");
        }

        // ====== Sequences ======
        System.out.println("\n--- Sequences ---");
        int seqSize = 220; // MdlParser's SEQDESC_SIZE_V49
        for (int i = 0; i < numlocalseq; i++) {
            int off = localseqindex + i * seqSize;
            if (off + seqSize > limit) break;
            int nameOff = buf.getInt(off + 4);
            int numFrames = buf.getInt(off + 28);
            int flags = buf.getInt(off + 84);
            String sName = nameOff > 0 ? readNullTermStr(buf, off + nameOff, limit) : "";
            System.out.println("  [" + i + "] name='" + sName + "' frames=" + numFrames + " flags=0x" + Integer.toHexString(flags));
        }

        // ====== Textures ======
        System.out.println("\n--- Textures ---");
        int texEntrySize = 64;
        for (int i = 0; i < numtextures; i++) {
            int off = textureindex + i * texEntrySize;
            if (off + texEntrySize > limit) break;
            int nameOff = buf.getInt(off);
            int texFlags = buf.getInt(off + 4);
            int texW = buf.getInt(off + 8);
            int texH = buf.getInt(off + 12);
            String tName = nameOff > 0 ? readNullTermStr(buf, off + nameOff, limit) : "";
            System.out.println("  [" + i + "] name='" + tName + "' flags=0x" + Integer.toHexString(texFlags)
                + " " + texW + "x" + texH);
        }

        // ====== Skin Table ======
        System.out.println("\n--- Skin Table (families=" + numskinfamilies + ", refs=" + numskinref + ") ---");
        int totalSkinEntries = numskinref * numskinfamilies;
        for (int i = 0; i < Math.min(totalSkinEntries, 48); i++) {
            int off = skinindex + i * 2;
            if (off + 2 > limit) break;
            int entry = buf.getShort(off) & 0xFFFF;
            System.out.print(" " + entry);
            if ((i + 1) % numskinref == 0) System.out.println();
        }
        System.out.println();

        // Check mesh count per model from model entries
        System.out.println("\n--- Verified actual totals ---");
        int totalMeshesExpected = 0;
        int skinEntryCount = 0;
        System.out.println("Total texture entries in MDL texture section: " + numtextures);
        System.out.println("Total skin entries: " + totalSkinEntries);
        System.out.println("Total include models: " + numincludemodels);

        // Check surface prop
        if (surfacepropindex > 0 && surfacepropindex < limit) {
            String sp = readNullTermStr(buf, surfacepropindex, limit);
            System.out.println("surfaceProp='" + sp + "'");
        }
    }

    // ======================== VVD ========================

    static void dumpVvd(String path) throws IOException {
        ByteBuffer buf = load(path);
        int limit = buf.limit();
        System.out.println("\n========================================");
        System.out.println("VVD: " + path + " (" + limit + " bytes)");
        System.out.println("========================================");

        int id = buf.getInt(0);
        int ver = buf.getInt(4);
        System.out.println("id=0x" + Integer.toHexString(id) + " (expected 0x56534449=IDSV) ver=" + ver);
        if (id != 0x56534449) {
            System.out.println("ERROR: not a valid VVD file");
            return;
        }

        int checksum = buf.getInt(8);
        int numLODs = buf.getInt(12);
        int[] lodVerts = new int[8];
        for (int i = 0; i < 8; i++) lodVerts[i] = buf.getInt(16 + i * 4);
        int numFixups = buf.getInt(48);
        int fixupTableStart = buf.getInt(52);
        int vertexDataStart = buf.getInt(56);
        int tangentDataStart = buf.getInt(60);

        System.out.println("checksum=0x" + Integer.toHexString(checksum));
        System.out.println("numLODs=" + numLODs);
        System.out.println("numLODVertices[0]=" + lodVerts[0] + " (tess=" + lodVerts[1] + " " + lodVerts[2] + " " + lodVerts[3] + ")");
        System.out.println("numFixups=" + numFixups);
        System.out.println("fixupTableStart=0x" + Integer.toHexString(fixupTableStart));
        System.out.println("vertexDataStart=0x" + Integer.toHexString(vertexDataStart));
        System.out.println("tangentDataStart=0x" + Integer.toHexString(tangentDataStart));

        // Fixups
        System.out.println("\n--- Fixups (" + numFixups + ") ---");
        for (int i = 0; i < Math.min(numFixups, 20); i++) {
            int off = fixupTableStart + i * 12;
            if (off + 12 > limit) break;
            int lod = buf.getInt(off);
            int srcId = buf.getInt(off + 4);
            int numV = buf.getInt(off + 8);
            System.out.println("  [" + i + "] lod=" + lod + " srcVertexID=" + srcId + " numVertexes=" + numV);
        }

        // First 5 vertices
        System.out.println("\n--- First 5 VVD vertices ---");
        int vvdVertSize = 48;
        for (int i = 0; i < Math.min(lodVerts[0], 5); i++) {
            int off = vertexDataStart + i * vvdVertSize;
            if (off + vvdVertSize > limit) break;
            float w0 = buf.getFloat(off);
            float w1 = buf.getFloat(off + 4);
            float w2 = buf.getFloat(off + 8);
            int b0 = buf.get(off + 12) & 0xFF;
            int b1 = buf.get(off + 13) & 0xFF;
            int b2 = buf.get(off + 14) & 0xFF;
            int numBones = buf.get(off + 15) & 0xFF;
            float x = buf.getFloat(off + 16);
            float y = buf.getFloat(off + 20);
            float z = buf.getFloat(off + 24);
            float nx = buf.getFloat(off + 28);
            float ny = buf.getFloat(off + 32);
            float nz = buf.getFloat(off + 36);
            float u = buf.getFloat(off + 40);
            float v = buf.getFloat(off + 44);
            System.out.println("  [" + i + "] pos=(" + x + "," + y + "," + z + ") nrm=(" + nx + "," + ny + "," + nz + ")"
                + " uv=(" + u + "," + v + ")");
            System.out.println("       weights=[" + w0 + "," + w1 + "," + w2 + "] bones=[" + b0 + "," + b1 + "," + b2 + "] numbones=" + numBones);
        }
    }

    // ======================== VTX ========================

    static void dumpVtx(String path) throws IOException {
        ByteBuffer buf = load(path);
        int limit = buf.limit();
        System.out.println("\n========================================");
        System.out.println("VTX: " + path + " (" + limit + " bytes)");
        System.out.println("========================================");

        int version = buf.getInt(0);
        int vertCacheSize = buf.getInt(4);
        int maxBonesPerStrip = buf.getShort(8) & 0xFFFF;
        int maxBonesPerTri = buf.getShort(10) & 0xFFFF;
        int maxBonesPerVert = buf.getInt(12);
        int checksum = buf.getInt(16);
        int numLODs = buf.getInt(20);
        int matReplListOff = buf.getInt(24);
        int numBodyParts = buf.getInt(28);
        int bodyPartOff = buf.getInt(32);

        System.out.println("version=" + version + " (expected 7)");
        System.out.println("vertCacheSize=" + vertCacheSize);
        System.out.println("maxBonesPerStrip=" + maxBonesPerStrip + " maxBonesPerTri=" + maxBonesPerTri + " maxBonesPerVert=" + maxBonesPerVert);
        System.out.println("checksum=0x" + Integer.toHexString(checksum));
        System.out.println("numLODs=" + numLODs + " numBodyParts=" + numBodyParts);
        System.out.println("bodyPartOffset=0x" + Integer.toHexString(bodyPartOff));

        int meshHeaderSize = (version >= 7) ? 9 : 8;
        int vertexStride = (version >= 7) ? maxBonesPerVert * 2 + 3 : 8;
        System.out.println("meshHeaderSize=" + meshHeaderSize + " vertexStride=" + vertexStride);

        int totalMeshes = 0;
        int totalTris = 0;

        for (int bp = 0; bp < numBodyParts; bp++) {
            int bhAddr = bodyPartOff + bp * 8;
            if (bhAddr + 8 > limit) break;
            int numModels = buf.getInt(bhAddr);
            int modelOff = buf.getInt(bhAddr + 4);
            int modelAddr = bhAddr + modelOff;
            System.out.println("\n  BodyPart[" + bp + "]: numModels=" + numModels + " modelAddr=0x" + Integer.toHexString(modelAddr));

            for (int m = 0; m < numModels; m++) {
                int mhAddr = modelAddr + m * 8;
                if (mhAddr + 8 > limit) break;
                int numLOD = buf.getInt(mhAddr);
                int lodOff = buf.getInt(mhAddr + 4);
                int lodAddr = mhAddr + lodOff;
                System.out.println("    Model[" + m + "]: numLODs=" + numLOD + " lodAddr=0x" + Integer.toHexString(lodAddr));

                for (int l = 0; l < Math.min(numLOD, 4); l++) {
                    int lhAddr = lodAddr + l * 12;
                    if (lhAddr + 12 > limit) break;
                    int numMeshes = buf.getInt(lhAddr);
                    int meshOff = buf.getInt(lhAddr + 4);
                    float switchPoint = buf.getFloat(lhAddr + 8);
                    int meshAddr = lhAddr + meshOff;
                    System.out.println("      LOD[" + l + "]: numMeshes=" + numMeshes + " switchPoint=" + switchPoint);

                    for (int meshIdx = 0; meshIdx < numMeshes; meshIdx++) {
                        int mh = meshAddr + meshIdx * meshHeaderSize;
                        if (mh + meshHeaderSize > limit) break;
                        int numSG = buf.getInt(mh);
                        int sgOff = buf.getInt(mh + 4);
                        int sgAddr = mh + sgOff;
                        System.out.println("        Mesh[" + meshIdx + "]: numStripGroups=" + numSG + " sgAddr=0x" + Integer.toHexString(sgAddr));

                        int meshTris = 0;
                        for (int sg = 0; sg < Math.min(numSG, 5); sg++) {
                            int shAddr = sgAddr + sg * 25;
                            if (shAddr + 25 > limit) break;
                            int numVerts = buf.getInt(shAddr);
                            int vertOff = buf.getInt(shAddr + 4);
                            int numIndices = buf.getInt(shAddr + 8);
                            int idxOff = buf.getInt(shAddr + 12);
                            int numStrips = buf.getInt(shAddr + 16);
                            int stripOff = buf.getInt(shAddr + 20);
                            System.out.println("          StripGroup[" + sg + "]: numVerts=" + numVerts + " numIndices=" + numIndices + " numStrips=" + numStrips);

                            int stripLimit = Math.min(numStrips, 5);
                            for (int s = 0; s < stripLimit; s++) {
                                int stAddr = (sgAddr + sgOff + stripOff) + s * 27;
                                if (stAddr + 27 > limit) {
                                    stAddr = shAddr + stripOff + s * 27;
                                }
                                if (stAddr + 27 > limit) continue;
                                int sNumIdx = buf.getInt(stAddr);
                                int sIdxOff = buf.getInt(stAddr + 4);
                                int sFlags = buf.get(stAddr + 18) & 0xFF;
                                boolean isTriList = (sFlags & 0x01) != 0;
                                System.out.println("            Strip[" + s + "]: numIndices=" + sNumIdx + " idxOff=" + sIdxOff + " flags=0x" + Integer.toHexString(sFlags) + (isTriList ? " [TRI_LIST]" : " [TRI_STRIP]"));
                                if (isTriList) meshTris += sNumIdx / 3;
                                else meshTris += sNumIdx - 2;
                            }
                        }
                        if (l == 0) {
                            totalMeshes++;
                            totalTris += meshTris;
                            System.out.println("        => ~" + meshTris + " triangles");
                        }
                    }
                }
            }
        }
        System.out.println("\n  TOTAL: " + totalMeshes + " meshes (LOD 0), ~" + totalTris + " triangles");
    }

    // ======================== Utilities ========================

    static String readFixedString(ByteBuffer buf, int offset) {
        if (offset < 0) return "";
        int safeLen = Math.min(64, buf.limit() - offset);
        if (safeLen <= 0) return "";
        int saved = buf.position();
        try {
            buf.position(offset);
            byte[] bytes = new byte[safeLen];
            buf.get(bytes);
            int nullTerm = 0;
            while (nullTerm < bytes.length && bytes[nullTerm] != 0) nullTerm++;
            return decodeString(bytes, nullTerm);
        } finally {
            buf.position(saved);
        }
    }

    static String readNullTermStr(ByteBuffer buf, int offset, int limit) {
        if (offset < 0 || offset >= limit) return "";
        int saved = buf.position();
        try {
            buf.position(offset);
            var baos = new ByteArrayOutputStream();
            int count = 0;
            while (count < 256 && buf.position() < limit) {
                byte b = buf.get();
                if (b == 0) break;
                baos.write(b);
                count++;
            }
            return decodeString(baos.toByteArray(), baos.size());
        } finally {
            buf.position(saved);
        }
    }

    static float[] readFloat3(ByteBuffer buf) {
        return new float[]{buf.getFloat(80), buf.getFloat(80 + 4), buf.getFloat(80 + 8)};
    }

    static String decodeString(byte[] bytes, int len) {
        if (len <= 0) return "";
        boolean hasHighBytes = false;
        for (int i = 0; i < len; i++) {
            if ((bytes[i] & 0xFF) > 0x7F) { hasHighBytes = true; break; }
        }
        if (!hasHighBytes) return new String(bytes, 0, len, ASCII);
        try {
            String cp932r = new String(bytes, 0, len, CP932);
            String cp1252r = new String(bytes, 0, len, CP1252);
            int s1 = calcScore(cp932r), s2 = calcScore(cp1252r);
            return s2 >= s1 ? cp1252r : cp932r;
        } catch (Exception e) {
            return new String(bytes, 0, len, ASCII);
        }
    }

    static int calcScore(String s) {
        int score = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) score += 2;
            else if (c == '/' || c == '_' || c == '-' || c == '.' || Character.isSpaceChar(c)) score += 1;
            else if (c == '?' || c == '\ufffd') score -= 10;
        }
        return score;
    }
}
