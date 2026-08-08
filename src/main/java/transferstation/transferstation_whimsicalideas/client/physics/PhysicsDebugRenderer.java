package transferstation.transferstation_whimsicalideas.client.physics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import transferstation.transferstation_whimsicalideas.client.model.PhysicsBridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug renderer for physics objects. Draws collision shapes, velocity vectors,
 * spatial grid cells, and trigger volumes for visual debugging.
 */
public final class PhysicsDebugRenderer {

    private static final List<DebugLine> lines = new ArrayList<>();
    private static final List<DebugBox> boxes = new ArrayList<>();
    private static final List<DebugSphere> spheres = new ArrayList<>();
    private static boolean enabled = false;

    private PhysicsDebugRenderer() {}

    public static void setEnabled(boolean enabled) {
        PhysicsDebugRenderer.enabled = enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void addLine(float x1, float y1, float z1,
                               float x2, float y2, float z2,
                               float r, float g, float b, float a) {
        if (!enabled) return;
        lines.add(new DebugLine(x1, y1, z1, x2, y2, z2, r, g, b, a));
    }

    public static void addVelocityVector(float x, float y, float z,
                                          float vx, float vy, float vz,
                                          float scale) {
        if (!enabled) return;
        float len = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 0.001f) return;
        addLine(x, y, z,
                x + vx * scale, y + vy * scale, z + vz * scale,
                0.2f, 1.0f, 0.2f, 1.0f);
    }

    public static void addAABB(float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ,
                                float r, float g, float b, float a) {
        if (!enabled) return;
        boxes.add(new DebugBox(minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a));
    }

    public static void addSphere(float cx, float cy, float cz, float radius,
                                  float r, float g, float b, float a) {
        if (!enabled) return;
        spheres.add(new DebugSphere(cx, cy, cz, radius, r, g, b, a));
    }

    public static void addCollisionShape(long bodyId, int shapeType, float[] params,
                                          float r, float g, float b) {
        if (!enabled || !PhysicsBridge.isAvailable()) return;

        float[] pos = PhysicsBridge.getPosition(bodyId);
        float px = pos[0], py = pos[1], pz = pos[2];

        switch (shapeType) {
            case PhysicsBridge.SHAPE_BOX:
                if (params.length >= 3) {
                    float hx = params[0] * 0.5f;
                    float hy = params[1] * 0.5f;
                    float hz = params[2] * 0.5f;
                    addAABB(px - hx, py - hy, pz - hz,
                            px + hx, py + hy, pz + hz,
                            r, g, b, 0.4f);
                    drawWireBox(px, py, pz, hx, hy, hz, r, g, b, 1.0f);
                }
                break;
            case PhysicsBridge.SHAPE_SPHERE:
                if (params.length >= 1) {
                    addSphere(px, py, pz, params[0], r, g, b, 0.3f);
                    drawWireSphere(px, py, pz, params[0], r, g, b, 1.0f);
                }
                break;
            case PhysicsBridge.SHAPE_CAPSULE:
                if (params.length >= 2) {
                    float capsuleRadius = params[0];
                    float capsuleHeight = params[1];
                    addCapsuleDebug(px, py, pz, capsuleRadius, capsuleHeight, r, g, b);
                }
                break;
        }
    }

    public static void render(PoseStack poseStack, float partialTick) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        if (!lines.isEmpty()) {
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (DebugLine line : lines) {
                bb.vertex(matrix,
                        (float)(line.x1 - camPos.x), (float)(line.y1 - camPos.y), (float)(line.z1 - camPos.z))
                        .color(line.r, line.g, line.b, line.a).endVertex();
                bb.vertex(matrix,
                        (float)(line.x2 - camPos.x), (float)(line.y2 - camPos.y), (float)(line.z2 - camPos.z))
                        .color(line.r, line.g, line.b, line.a).endVertex();
            }
            BufferUploader.drawWithShader(bb.end());
        }

        if (!boxes.isEmpty()) {
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (DebugBox box : boxes) {
                addBoxEdges(bb, box, camPos, matrix);
            }
            BufferUploader.drawWithShader(bb.end());
        }

        if (!spheres.isEmpty()) {
            BufferBuilder bb = Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
            for (DebugSphere sphere : spheres) {
                addSphereEdges(bb, sphere, camPos, matrix, 16);
            }
            BufferUploader.drawWithShader(bb.end());
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        lines.clear();
        boxes.clear();
        spheres.clear();
    }

    private static void drawWireBox(float cx, float cy, float cz,
                                     float hx, float hy, float hz,
                                     float r, float g, float b, float a) {
        if (!enabled) return;
        lines.add(new DebugLine(cx - hx, cy - hy, cz - hz, cx + hx, cy - hy, cz - hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy - hy, cz - hz, cx + hx, cy - hy, cz + hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy - hy, cz + hz, cx - hx, cy - hy, cz + hz, r, g, b, a));
        lines.add(new DebugLine(cx - hx, cy - hy, cz + hz, cx - hx, cy - hy, cz - hz, r, g, b, a));

        lines.add(new DebugLine(cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy + hy, cz - hz, cx + hx, cy + hy, cz + hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, r, g, b, a));
        lines.add(new DebugLine(cx - hx, cy + hy, cz + hz, cx - hx, cy + hy, cz - hz, r, g, b, a));

        lines.add(new DebugLine(cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy - hy, cz - hz, cx + hx, cy + hy, cz - hz, r, g, b, a));
        lines.add(new DebugLine(cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, r, g, b, a));
        lines.add(new DebugLine(cx - hx, cy - hy, cz + hz, cx - hx, cy + hy, cz + hz, r, g, b, a));
    }

    private static void drawWireSphere(float cx, float cy, float cz, float radius,
                                        float r, float g, float b, float a) {
        if (!enabled) return;
        int segments = 16;
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < segments; i++) {
                float angle1 = (float) (i * 2 * Math.PI / segments);
                float angle2 = (float) ((i + 1) * 2 * Math.PI / segments);

                float cos1 = (float) Math.cos(angle1);
                float sin1 = (float) Math.sin(angle1);
                float cos2 = (float) Math.cos(angle2);
                float sin2 = (float) Math.sin(angle2);

                float x1w, y1w, z1w, x2w, y2w, z2w;
                if (axis == 0) {
                    x1w = cx; y1w = cy + sin1 * radius; z1w = cz + cos1 * radius;
                    x2w = cx; y2w = cy + sin2 * radius; z2w = cz + cos2 * radius;
                } else if (axis == 1) {
                    x1w = cx + cos1 * radius; y1w = cy; z1w = cz + sin1 * radius;
                    x2w = cx + cos2 * radius; y2w = cy; z2w = cz + sin2 * radius;
                } else {
                    x1w = cx + cos1 * radius; y1w = cy + sin1 * radius; z1w = cz;
                    x2w = cx + cos2 * radius; y2w = cy + sin2 * radius; z2w = cz;
                }
                lines.add(new DebugLine(x1w, y1w, z1w, x2w, y2w, z2w, r, g, b, a));
            }
        }
    }

    private static void addCapsuleDebug(float cx, float cy, float cz,
                                         float radius, float height,
                                         float r, float g, float b) {
        float halfHeight = height * 0.5f;
        drawWireSphere(cx, cy + halfHeight, cz, radius, r, g, b, 0.8f);
        drawWireSphere(cx, cy - halfHeight, cz, radius, r, g, b, 0.8f);

        lines.add(new DebugLine(cx - radius, cy - halfHeight, cz,
                cx - radius, cy + halfHeight, cz, r, g, b, 0.6f));
        lines.add(new DebugLine(cx + radius, cy - halfHeight, cz,
                cx + radius, cy + halfHeight, cz, r, g, b, 0.6f));
        lines.add(new DebugLine(cx, cy - halfHeight, cz - radius,
                cx, cy + halfHeight, cz - radius, r, g, b, 0.6f));
        lines.add(new DebugLine(cx, cy - halfHeight, cz + radius,
                cx, cy + halfHeight, cz + radius, r, g, b, 0.6f));
    }

    private static void addBoxEdges(BufferBuilder bb, DebugBox box, Vec3 cam, Matrix4f matrix) {
        float x0 = (float)(box.minX - cam.x);
        float y0 = (float)(box.minY - cam.y);
        float z0 = (float)(box.minZ - cam.z);
        float x1 = (float)(box.maxX - cam.x);
        float y1 = (float)(box.maxY - cam.y);
        float z1 = (float)(box.maxZ - cam.z);

        bb.vertex(matrix, x0, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();

        bb.vertex(matrix, x0, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();

        bb.vertex(matrix, x0, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z0).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x1, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y0, z1).color(box.r, box.g, box.b, box.a).endVertex();
        bb.vertex(matrix, x0, y1, z1).color(box.r, box.g, box.b, box.a).endVertex();
    }

    private static void addSphereEdges(BufferBuilder bb, DebugSphere sphere, Vec3 cam, Matrix4f matrix, int segments) {
        float cx = (float)(sphere.cx - cam.x);
        float cy = (float)(sphere.cy - cam.y);
        float cz = (float)(sphere.cz - cam.z);
        float r = sphere.radius;

        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < segments; i++) {
                float angle1 = (float) (i * 2 * Math.PI / segments);
                float angle2 = (float) ((i + 1) * 2 * Math.PI / segments);

                float cos1 = (float) Math.cos(angle1);
                float sin1 = (float) Math.sin(angle1);
                float cos2 = (float) Math.cos(angle2);
                float sin2 = (float) Math.sin(angle2);

                float x1, y1, z1, x2, y2, z2;
                if (axis == 0) {
                    x1 = cx; y1 = cy + sin1 * r; z1 = cz + cos1 * r;
                    x2 = cx; y2 = cy + sin2 * r; z2 = cz + cos2 * r;
                } else if (axis == 1) {
                    x1 = cx + cos1 * r; y1 = cy; z1 = cz + sin1 * r;
                    x2 = cx + cos2 * r; y2 = cy; z2 = cz + sin2 * r;
                } else {
                    x1 = cx + cos1 * r; y1 = cy + sin1 * r; z1 = cz;
                    x2 = cx + cos2 * r; y2 = cy + sin2 * r; z2 = cz;
                }
                bb.vertex(matrix, x1, y1, z1).color(sphere.r, sphere.g, sphere.b, sphere.a).endVertex();
                bb.vertex(matrix, x2, y2, z2).color(sphere.r, sphere.g, sphere.b, sphere.a).endVertex();
            }
        }
    }

    public static void renderTriggerVolume(TriggerVolume volume) {
        if (!enabled) return;
        Vector3f center = volume.getCenter();
        if (volume.getShape() == TriggerVolume.Shape.BOX) {
            Vector3f he = volume.getHalfExtents();
            addAABB(center.x - he.x, center.y - he.y, center.z - he.z,
                    center.x + he.x, center.y + he.y, center.z + he.z,
                    0.2f, 0.8f, 1.0f, 0.3f);
            drawWireBox(center.x, center.y, center.z, he.x, he.y, he.z,
                    0.2f, 0.8f, 1.0f, 0.8f);
        } else {
            addSphere(center.x, center.y, center.z, volume.getRadius(),
                    0.2f, 0.8f, 1.0f, 0.3f);
            drawWireSphere(center.x, center.y, center.z, volume.getRadius(),
                    0.2f, 0.8f, 1.0f, 0.8f);
        }
    }

    public static void renderSpatialGrid(SpatialHashGrid grid) {
        if (!enabled) return;
        float alpha = 0.08f;

        addLine(-50, 0, -50, 50, 0, -50, 0.5f, 0.5f, 0.5f, alpha);
        addLine(-50, 0, 50, 50, 0, 50, 0.5f, 0.5f, 0.5f, alpha);
        addLine(-50, 0, -50, -50, 0, 50, 0.5f, 0.5f, 0.5f, alpha);
        addLine(50, 0, -50, 50, 0, 50, 0.5f, 0.5f, 0.5f, alpha);
    }

    private record DebugLine(float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float r, float g, float b, float a) {}

    private record DebugBox(float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ,
                             float r, float g, float b, float a) {}

    private record DebugSphere(float cx, float cy, float cz, float radius,
                                float r, float g, float b, float a) {}
}
