package transferstation.transferstation_whimsicalideas.client.animation;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Triggers callbacks at specific animation frames during playback.
 * Events are registered per animation name and fire once per playback cycle.
 */
public final class AnimationEventSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Registered events keyed by animation name. */
    private static final Map<String, List<AnimationEvent>> eventRegistry = new ConcurrentHashMap<>();

    /** Tracks which frame indices have already fired for each entity+animation combination this cycle. */
    private static final Map<String, Set<Integer>> firedThisCycle = new ConcurrentHashMap<>();

    private AnimationEventSystem() {
    }

    // ── Registration ──────────────────────────────────────────────────────

    /**
     * Register a frame event for the given animation.
     *
     * @param animationName name of the animation (must match registry key)
     * @param frame         frame number (0-based) at which to trigger
     * @param listener      callback receiving the entity and frame number
     */
    public static void registerEvent(String animationName, int frame, Consumer<LivingEntity> listener) {
        Objects.requireNonNull(animationName, "animationName");
        Objects.requireNonNull(listener, "listener");
        eventRegistry.computeIfAbsent(animationName, k -> new ArrayList<>())
                .add(new AnimationEvent(frame, listener));
    }

    /**
     * Convenience: register a named event type (PARTICLE, SOUND, CUSTOM, etc.).
     */
    public static void registerTypedEvent(String animationName, int frame, EventType type, Consumer<LivingEntity> listener) {
        Objects.requireNonNull(animationName, "animationName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(listener, "listener");
        eventRegistry.computeIfAbsent(animationName, k -> new ArrayList<>())
                .add(new AnimationEvent(frame, type, listener));
    }

    /**
     * Remove all events for the given animation.
     */
    public static void clearEvents(String animationName) {
        eventRegistry.remove(animationName);
    }

    /**
     * Remove all registered events.
     */
    public static void clearAll() {
        eventRegistry.clear();
        firedThisCycle.clear();
    }

    // ── Playback ──────────────────────────────────────────────────────────

    /**
     * Called each render frame. Checks whether any registered event should fire
     * for the given entity's current animation and frame.
     *
     * @param entity         the entity being animated
     * @param animationName  current animation name
     * @param currentFrame   current integer frame (already computed by AnimationProcessor)
     */
    public static void tickEvents(LivingEntity entity, String animationName, int currentFrame) {
        if (animationName == null || entity == null) return;

        List<AnimationEvent> events = eventRegistry.get(animationName);
        if (events == null || events.isEmpty()) return;

        String cycleKey = entity.getId() + ":" + animationName;
        Set<Integer> fired = firedThisCycle.computeIfAbsent(cycleKey, k -> new HashSet<>());

        for (AnimationEvent event : events) {
            if (event.frame == currentFrame && !fired.contains(event.frame)) {
                fired.add(event.frame);
                try {
                    event.listener.accept(entity);
                } catch (Exception e) {
                    LOGGER.error("[AnimationEventSystem] Error firing event at frame {} for '{}': {}",
                            event.frame, animationName, e.getMessage());
                }
            }
        }
    }

    /**
     * Reset the fired-this-cycle tracking for an entity when its animation changes
     * or loops. Should be called when the animation name changes or when the frame
     * wraps around (loop restart).
     */
    public static void resetCycle(LivingEntity entity, String animationName) {
        if (entity == null || animationName == null) return;
        String cycleKey = entity.getId() + ":" + animationName;
        firedThisCycle.remove(cycleKey);
    }

    /**
     * Remove all cycle tracking for an entity (call on entity death/despawn).
     */
    public static void clearEntity(LivingEntity entity) {
        if (entity == null) return;
        String prefix = entity.getId() + ":";
        firedThisCycle.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ── Query ─────────────────────────────────────────────────────────────

    public static boolean hasEvents(String animationName) {
        List<AnimationEvent> events = eventRegistry.get(animationName);
        return events != null && !events.isEmpty();
    }

    public static List<AnimationEvent> getEvents(String animationName) {
        List<AnimationEvent> events = eventRegistry.get(animationName);
        return events != null ? Collections.unmodifiableList(events) : Collections.emptyList();
    }

    // ── Types ─────────────────────────────────────────────────────────────

    public enum EventType {
        /** Generic frame callback. */
        CUSTOM,
        /** Trigger a particle effect. */
        PARTICLE,
        /** Trigger a sound effect. */
        SOUND,
        /** Trigger a screen shake or visual effect. */
        VISUAL
    }

    /**
     * A single frame event bound to an animation.
     */
    public static class AnimationEvent {
        public final int frame;
        public final EventType type;
        public final Consumer<LivingEntity> listener;

        public AnimationEvent(int frame, Consumer<LivingEntity> listener) {
            this(frame, EventType.CUSTOM, listener);
        }

        public AnimationEvent(int frame, EventType type, Consumer<LivingEntity> listener) {
            this.frame = frame;
            this.type = type;
            this.listener = listener;
        }
    }
}
