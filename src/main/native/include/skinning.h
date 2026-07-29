#ifndef SKINNING_H
#define SKINNING_H

#include "matrix_math.h"
#include "studio_types.h"
#include <vector>

struct SkinnedVertex {
    float x, y, z;
    float nx, ny, nz;
    float u, v;
};

void skinVertex(
    const StudioVertexExt& src,
    const Matrix4x4* boneTransforms,
    const Matrix4x4* invBindPose,
    int numBones,
    SkinnedVertex& out
);

void skinVertices(
    const std::vector<StudioVertexExt>& srcVerts,
    const Matrix4x4* boneTransforms,
    const Matrix4x4* invBindPose,
    int numBones,
    std::vector<SkinnedVertex>& outVerts
);

#endif // SKINNING_H
