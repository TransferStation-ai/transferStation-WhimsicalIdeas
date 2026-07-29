#include "skinning.h"

void skinVertex(
    const StudioVertexExt& src,
    const Matrix4x4* boneTransforms,
    const Matrix4x4* invBindPose,
    int numBones,
    SkinnedVertex& out)
{
    float restPos[3] = { src.x, src.y, src.z };
    float restNrm[3] = { src.nx, src.ny, src.nz };

    float skinnedPos[3] = { 0.0f, 0.0f, 0.0f };
    float skinnedNrm[3] = { 0.0f, 0.0f, 0.0f };

    int numInfluences = (src.numBones > 0 && src.numBones <= 3) ? src.numBones : 1;

    float totalWeight = 0.0f;
    for (int i = 0; i < numInfluences; i++) {
        totalWeight += src.boneWeight[i];
    }
    if (totalWeight < 0.001f) {
        totalWeight = 1.0f;
        numInfluences = 1;
    }

    for (int i = 0; i < numInfluences; i++) {
        int boneIdx = src.boneId[i];
        if (boneIdx < 0 || boneIdx >= numBones) continue;

        float w = src.boneWeight[i] / totalWeight;

        Matrix4x4 skinMat = Matrix4x4::multiply(boneTransforms[boneIdx], invBindPose[boneIdx]);

        float bonePos[3], boneNrm[3];
        skinMat.transformPoint(restPos, bonePos);
        skinMat.transformNormal(restNrm, boneNrm);

        skinnedPos[0] += w * bonePos[0];
        skinnedPos[1] += w * bonePos[1];
        skinnedPos[2] += w * bonePos[2];

        skinnedNrm[0] += w * boneNrm[0];
        skinnedNrm[1] += w * boneNrm[1];
        skinnedNrm[2] += w * boneNrm[2];
    }

    out.u = src.u;
    out.v = src.v;

    float len = sqrtf(skinnedNrm[0] * skinnedNrm[0] +
                      skinnedNrm[1] * skinnedNrm[1] +
                      skinnedNrm[2] * skinnedNrm[2]);
    if (len > 0.0001f) {
        out.nx = skinnedNrm[0] / len;
        out.ny = skinnedNrm[1] / len;
        out.nz = skinnedNrm[2] / len;
    } else {
        out.nx = 0.0f; out.ny = 1.0f; out.nz = 0.0f;
    }

    out.x = skinnedPos[0];
    out.y = skinnedPos[1];
    out.z = skinnedPos[2];
}

void skinVertices(
    const std::vector<StudioVertexExt>& srcVerts,
    const Matrix4x4* boneTransforms,
    const Matrix4x4* invBindPose,
    int numBones,
    std::vector<SkinnedVertex>& outVerts)
{
    size_t count = srcVerts.size();
    outVerts.resize(count);

    for (size_t i = 0; i < count; i++) {
        skinVertex(srcVerts[i], boneTransforms, invBindPose, numBones, outVerts[i]);
    }
}
