package transferstation.transferstation_whimsicalideas.client.model;

import java.util.*;

public class MeshDecimator {

    private static class Quadric {
        double a2, b2, c2, d2;
        double ab, ac, ad, bc, bd, cd;

        Quadric() {}

        Quadric(double a, double b, double c, double d) {
            a2 = a * a; b2 = b * b; c2 = c * c; d2 = d * d;
            ab = a * b; ac = a * c; ad = a * d;
            bc = b * c; bd = b * d;
            cd = c * d;
        }

        Quadric add(Quadric o) {
            Quadric r = new Quadric();
            r.a2 = a2 + o.a2; r.b2 = b2 + o.b2; r.c2 = c2 + o.c2; r.d2 = d2 + o.d2;
            r.ab = ab + o.ab; r.ac = ac + o.ac; r.ad = ad + o.ad;
            r.bc = bc + o.bc; r.bd = bd + o.bd; r.cd = cd + o.cd;
            return r;
        }

        double evaluate(double x, double y, double z) {
            return x * x * a2 + y * y * b2 + z * z * c2 + d2
                + 2 * (x * y * ab + x * z * ac + x * ad + y * z * bc + y * bd + z * cd);
        }
    }

    private static class Vertex {
        int id;
        double x, y, z;
        double u, v;
        double nx, ny, nz;
        Quadric q;
        List<Integer> faces = new ArrayList<>();
        boolean deleted;
    }

    private static class Face {
        int v0, v1, v2;
        boolean deleted;
    }

    private static class Edge implements Comparable<Edge> {
        int v0, v1;
        double cost;

        Edge(int v0, int v1) {
            this.v0 = Math.min(v0, v1);
            this.v1 = Math.max(v0, v1);
        }

        @Override
        public int compareTo(Edge o) {
            return Double.compare(this.cost, o.cost);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edge e)) return false;
            return v0 == e.v0 && v1 == e.v1;
        }

        @Override
        public int hashCode() {
            return v0 * 31 + v1;
        }
    }

    public static SourceModelData.MeshData decimate(
            SourceModelData.MeshData original, int targetTriCount) {
        if (original == null || original.indices == null) return null;
        int triCount = original.indices.length / 3;
        if (targetTriCount <= 0) targetTriCount = 1;
        if (targetTriCount >= triCount) return original;

        float[] verts = original.vertices;
        int[] idx = original.indices;

        int numVerts = verts.length / 8;
        Map<Integer, Vertex> vertexMap = new HashMap<>();
        List<Face> faces = new ArrayList<>();

        for (int i = 0; i < numVerts; i++) {
            Vertex v = new Vertex();
            v.id = i;
            v.x = verts[i * 8];
            v.y = verts[i * 8 + 1];
            v.z = verts[i * 8 + 2];
            v.nx = verts[i * 8 + 3];
            v.ny = verts[i * 8 + 4];
            v.nz = verts[i * 8 + 5];
            v.u = verts[i * 8 + 6];
            v.v = verts[i * 8 + 7];
            v.q = new Quadric();
            vertexMap.put(i, v);
        }

        for (int i = 0; i < idx.length; i += 3) {
            Face f = new Face();
            f.v0 = idx[i]; f.v1 = idx[i + 1]; f.v2 = idx[i + 2];
            faces.add(f);
            vertexMap.get(f.v0).faces.add(faces.size() - 1);
            vertexMap.get(f.v1).faces.add(faces.size() - 1);
            vertexMap.get(f.v2).faces.add(faces.size() - 1);
        }

        for (Face f : faces) {
            Vertex v0 = vertexMap.get(f.v0);
            Vertex v1 = vertexMap.get(f.v1);
            Vertex v2 = vertexMap.get(f.v2);
            double ax = v1.x - v0.x, ay = v1.y - v0.y, az = v1.z - v0.z;
            double bx = v2.x - v0.x, by = v2.y - v0.y, bz = v2.z - v0.z;
            double nx = ay * bz - az * by;
            double ny = az * bx - ax * bz;
            double nz = ax * by - ay * bx;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-10) continue;
            nx /= len; ny /= len; nz /= len;
            double d = -(nx * v0.x + ny * v0.y + nz * v0.z);
            Quadric q = new Quadric(nx, ny, nz, d);
            v0.q = v0.q.add(q);
            v1.q = v1.q.add(q);
            v2.q = v2.q.add(q);
        }

        Set<Edge> edgeSet = new HashSet<>();
        for (Face f : faces) {
            edgeSet.add(new Edge(f.v0, f.v1));
            edgeSet.add(new Edge(f.v1, f.v2));
            edgeSet.add(new Edge(f.v2, f.v0));
        }
        PriorityQueue<Edge> heap = new PriorityQueue<>();
        for (Edge e : edgeSet) {
            computeEdgeCost(e, vertexMap);
            heap.offer(e);
        }

        int targetFaces = targetTriCount;
        while (faces.size() > targetFaces && !heap.isEmpty()) {
            Edge e = heap.poll();
            if (e.cost == Double.MAX_VALUE) break;
            Vertex v0 = vertexMap.get(e.v0);
            Vertex v1 = vertexMap.get(e.v1);
            if (v0.deleted || v1.deleted) continue;

            boolean stillEdge = false;
            for (int fi : v0.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                if ((f.v0 == e.v1 || f.v1 == e.v1 || f.v2 == e.v1)) {
                    stillEdge = true;
                    break;
                }
            }
            if (!stillEdge) continue;

            double nx = (v0.nx + v1.nx) * 0.5;
            double ny = (v0.ny + v1.ny) * 0.5;
            double nz = (v0.nz + v1.nz) * 0.5;
            double nlen = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen > 1e-10) { nx /= nlen; ny /= nlen; nz /= nlen; }

            v0.x = (v0.x + v1.x) * 0.5;
            v0.y = (v0.y + v1.y) * 0.5;
            v0.z = (v0.z + v1.z) * 0.5;
            v0.nx = nx; v0.ny = ny; v0.nz = nz;
            v0.u = (v0.u + v1.u) * 0.5;
            v0.v = (v0.v + v1.v) * 0.5;
            v0.q = v0.q.add(v1.q);

            List<Integer> facesToRemove = new ArrayList<>();
            for (int fi : v1.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                boolean hasV0 = (f.v0 == e.v0 || f.v1 == e.v0 || f.v2 == e.v0);
                if (hasV0) {
                    facesToRemove.add(fi);
                } else {
                    if (f.v0 == e.v1) f.v0 = e.v0;
                    if (f.v1 == e.v1) f.v1 = e.v0;
                    if (f.v2 == e.v1) f.v2 = e.v0;
                    v0.faces.add(fi);
                }
            }
            for (int fi : facesToRemove) {
                Face f = faces.get(fi);
                f.deleted = true;
            }
            v1.deleted = true;

            Set<Integer> neighborSet = new HashSet<>();
            for (int fi : v0.faces) {
                if (fi >= faces.size()) continue;
                Face f = faces.get(fi);
                if (f.deleted) continue;
                neighborSet.add(f.v0); neighborSet.add(f.v1); neighborSet.add(f.v2);
            }
            for (int vid : neighborSet) {
                if (vid == e.v0) continue;
                Vertex nv = vertexMap.get(vid);
                if (nv == null || nv.deleted) continue;
                Edge ne = new Edge(e.v0, vid);
                computeEdgeCost(ne, vertexMap);
                heap.offer(ne);
            }
        }

        Set<Integer> usedVerts = new HashSet<>();
        List<Integer> activeFaces = new ArrayList<>();
        for (Face f : faces) {
            if (f.deleted) continue;
            activeFaces.add(f.v0);
            activeFaces.add(f.v1);
            activeFaces.add(f.v2);
            usedVerts.add(f.v0);
            usedVerts.add(f.v1);
            usedVerts.add(f.v2);
        }

        if (activeFaces.size() < 3) {
            Vertex v0 = vertexMap.get(0);
            float[] fv = new float[8 * 3];
            fv[0] = (float)v0.x; fv[1] = (float)v0.y; fv[2] = (float)v0.z;
            fv[3] = (float)v0.nx; fv[4] = (float)v0.ny; fv[5] = (float)v0.nz;
            fv[6] = (float)v0.u; fv[7] = (float)v0.v;
            System.arraycopy(fv, 0, fv, 8, 8);
            System.arraycopy(fv, 0, fv, 16, 8);
            fv[16 + 3] = 1;
            return new SourceModelData.MeshData.Builder()
                .vertices(fv).indices(new int[]{0, 1, 2})
                .texture(original.texture).normalMap(original.normalMap)
                .translucent(original.translucent).alphaTest(original.alphaTest)
                .noCull(original.noCull).selfIllum(original.selfIllum)
                .hasPhong(original.hasPhong).halfLambert(original.halfLambert)
                .phongBoost(original.phongBoost).vtfKey(original.vtfKey)
                .colorTint(original.colorTint).alpha(original.alpha)
                .surfaceProp(original.surfaceProp).detailBlendMode(original.detailBlendMode)
                .build();
        }

        Map<Integer, Integer> remap = new LinkedHashMap<>();
        for (int vi : activeFaces) {
            if (!remap.containsKey(vi)) {
                remap.put(vi, remap.size());
            }
        }
        int newVertCount = remap.size();
        float[] newVerts = new float[newVertCount * 8];
        for (Map.Entry<Integer, Integer> entry : remap.entrySet()) {
            Vertex v = vertexMap.get(entry.getKey());
            int dst = entry.getValue() * 8;
            newVerts[dst] = (float) v.x;
            newVerts[dst + 1] = (float) v.y;
            newVerts[dst + 2] = (float) v.z;
            newVerts[dst + 3] = (float) v.nx;
            newVerts[dst + 4] = (float) v.ny;
            newVerts[dst + 5] = (float) v.nz;
            newVerts[dst + 6] = (float) v.u;
            newVerts[dst + 7] = (float) v.v;
        }

        int[] newIdx = new int[activeFaces.size()];
        for (int i = 0; i < activeFaces.size(); i++) {
            newIdx[i] = remap.get(activeFaces.get(i));
        }

        return new SourceModelData.MeshData.Builder()
            .vertices(newVerts).indices(newIdx)
            .texture(original.texture).normalMap(original.normalMap)
            .ssbumpMap(original.ssbumpMap).envMapMask(original.envMapMask)
            .parallaxMap(original.parallaxMap).detailMap(original.detailMap)
            .selfIllumMask(original.selfIllumMask)
            .translucent(original.translucent).alphaTest(original.alphaTest)
            .noCull(original.noCull).selfIllum(original.selfIllum)
            .hasPhong(original.hasPhong).halfLambert(original.halfLambert)
            .phongBoost(original.phongBoost)
            .phongFresnelRanges(original.phongFresnelRanges)
            .phongExponentTexture(original.phongExponentTexture)
            .bodyPartIndex(original.bodyPartIndex).modelIndex(original.modelIndex)
            .materialIndex(original.materialIndex)
            .vtfKey(original.vtfKey).colorTint(original.colorTint)
            .alpha(original.alpha).surfaceProp(original.surfaceProp)
            .detailBlendMode(original.detailBlendMode)
            .build();
    }

    private static void computeEdgeCost(Edge e, Map<Integer, Vertex> vertexMap) {
        Vertex v0 = vertexMap.get(e.v0);
        Vertex v1 = vertexMap.get(e.v1);
        if (v0 == null || v1 == null || v0.deleted || v1.deleted) {
            e.cost = Double.MAX_VALUE;
            return;
        }
        double mx = (v0.x + v1.x) * 0.5;
        double my = (v0.y + v1.y) * 0.5;
        double mz = (v0.z + v1.z) * 0.5;
        Quadric q = v0.q.add(v1.q);
        e.cost = q.evaluate(mx, my, mz);

        int sharedFaces = 0;
        for (int fi : v0.faces) {
            if (fi >= v0.faces.size()) {
            }
        }
        Set<Integer> v0FaceSet = new HashSet<>(v0.faces);
        for (int fi : v1.faces) {
            if (v0FaceSet.contains(fi)) sharedFaces++;
        }
        if (sharedFaces <= 1) {
            e.cost *= 10.0;
        }
    }
}
