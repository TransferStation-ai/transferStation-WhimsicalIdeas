#ifndef PHY_PARSER_H
#define PHY_PARSER_H

#include <string>
#include <vector>
#include <cstdint>

class PhyParser {
public:
    struct PhyVertex {
        float x, y, z;
    };

    struct PhyTriangle {
        uint8_t vertexIndex;
        int16_t v1, v2, v3;
    };

    struct PhyConvexHull {
        int32_t vertexOffset;
        int32_t boneIndex;
        int32_t flags;
        int32_t triangleCount;
        std::vector<PhyTriangle> triangles;
        std::vector<PhyVertex> vertices;
    };

    struct PhySolid {
        int index;
        std::string name;
        int parent = -1;
        float mass = 0.0f;
        std::string surfaceprop;
        float damping = 0.0f;
        float rotdamping = 0.0f;
        float inertia = 0.0f;
        float volume = 0.0f;
        std::vector<PhyConvexHull> convexHulls;
    };

    // A joint reference parsed from the "ragdoll" -> "joints" section of the
    // .phy KeyValues: the child bone name and its parent bone name. The actual
    // constraint properties (pivot, axes, limits) live in the binary
    // ragdoll_constraint_t block which this parser does not decode yet.
    struct RagdollJointRef {
        std::string name;       // child (joint owner) bone
        std::string parentName; // parent bone
    };

    // One constraint from the "ragdollconstraint" KeyValues block. parent/child
    // reference solid indices; the names are resolved via the solid table.
    // Source maps X/Y/Z Euler ranges to swing/twist of a cone-twist joint:
    //   xmin/xmax -> swing1 (around parent local X)
    //   ymin/ymax -> swing2 (around parent local Y)
    //   zmin/zmax -> twist   (around parent local Z)
    struct RagdollConstraint {
        int parentIndex = -1;
        int childIndex = -1;
        std::string parentName;
        std::string childName;
        float xmin = 0.0f, xmax = 0.0f, xfriction = 0.0f;
        float ymin = 0.0f, ymax = 0.0f, yfriction = 0.0f;
        float zmin = 0.0f, zmax = 0.0f, zfriction = 0.0f;
        // Estimated joint pivot: child solid convex-hull centroid (world = local
        // at creation time, matching how NpcRagdoll builds local-frame anchors).
        float pivotX = 0.0f, pivotY = 0.0f, pivotZ = 0.0f;
    };

    struct ParsedPhy {
        int32_t size;
        std::string id;
        int32_t solidCount;
        int32_t checksum;
        std::vector<PhySolid> solids;
        std::vector<RagdollJointRef> ragdollJoints;
        std::vector<RagdollConstraint> ragdollConstraints;
        std::string rootName;
        float totalMass = 0.0f;
        bool valid;
    };

    static ParsedPhy parse(const std::vector<uint8_t>& data);
};

#endif // PHY_PARSER_H
