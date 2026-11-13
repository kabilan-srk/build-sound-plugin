package io.jenkins.plugins.buildsound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Keeps the latest finished-build events for each user, in memory only.
 *
 * <p>Each user keeps at most {@code maxPerUser} events, and an event is dropped once it is older than the retention
 * time. One lock guards everything: it is simple, and the store is small and quick.
 */
@Restricted(NoExternalUse.class)
public class EventStore {

    private static final int MAX_EVENTS_PER_USER = 20;
    // Long enough for a poll that is late (a busy browser, a hidden tab), short enough that old news is not played.
    private static final Duration RETENTION = Duration.ofMinutes(5);

    // One store for the whole Jenkins, shared by the listener and the endpoint.
    // System.nanoTime is a clock that only counts forward: unlike the wall clock it is not changed when someone
    // sets the time or the time zone, so events never live too long or vanish too early.
    private static final EventStore INSTANCE = new EventStore(MAX_EVENTS_PER_USER, RETENTION, System::nanoTime);

    public static EventStore instance() {
        return INSTANCE;
    }

    private final int maxPerUser;
    private final long retentionNanos;
    private final LongSupplier nanoClock; // tests give their own, so they can move time by hand

    private final Object lock = new Object();
    // Everything below is only touched while holding "lock".
    private final Map<String, LinkedList<BuildEvent>> eventsByUser = new HashMap<>();
    private long lastId = 0;

    public EventStore(int maxPerUser, Duration retention, LongSupplier nanoClock) {
        this.maxPerUser = maxPerUser;
        this.retentionNanos = retention.toNanos();
        this.nanoClock = nanoClock;
    }

    /** Stores an event for the user. Empty (and nothing stored) when the build has no real human user. */
    public Optional<BuildEvent> add(String userId, String jobFullName, int buildNumber, Sound sound) {
        if (!isRealUser(userId)) {
            return Optional.empty();
        }
        synchronized (lock) {
            long now = nanoClock.getAsLong();
            pruneExpired(now);
            // Ids are counted for all users together, so the ids one user sees have gaps. A gap only tells that
            // somebody else's event exists, never what it is or whose.
            lastId++;
            BuildEvent event = new BuildEvent(lastId, jobFullName, buildNumber, sound, now);
            LinkedList<BuildEvent> events = eventsByUser.computeIfAbsent(userId, key -> new LinkedList<>());
            events.addLast(event);
            while (events.size() > maxPerUser) {
                events.removeFirst();
            }
            return Optional.of(event);
        }
    }

    /**
     * This user's events with an id greater than {@code afterId}, oldest first. It is one read under one lock, so
     * the answer is a consistent snapshot: callers work out everything else (such as the next cursor) from this
     * list and never read the store a second time.
     */
    public List<BuildEvent> eventsSince(String userId, long afterId) {
        synchronized (lock) {
            pruneExpired(nanoClock.getAsLong());
            List<BuildEvent> result = new ArrayList<>();
            List<BuildEvent> events = eventsByUser.get(userId);
            if (events != null) {
                for (BuildEvent event : events) {
                    if (event.getId() > afterId) {
                        result.add(event);
                    }
                }
            }
            return result;
        }
    }

    /** For tests: forget all events. The id counter is not reset, so ids stay unique. */
    public void clear() {
        synchronized (lock) {
            eventsByUser.clear();
        }
    }

    private static boolean isRealUser(String userId) {
        return userId != null && !userId.trim().isEmpty() && !"SYSTEM".equals(userId) && !"anonymous".equals(userId);
    }

    // Caller must hold "lock". Events are in time order, so we can stop at the first one that is still fresh.
    // The clock values are compared as a difference (now - created), which stays right even if the nanoTime
    // counter wraps around.
    private void pruneExpired(long now) {
        Iterator<LinkedList<BuildEvent>> users = eventsByUser.values().iterator();
        while (users.hasNext()) {
            LinkedList<BuildEvent> events = users.next();
            while (!events.isEmpty() && now - events.getFirst().getCreatedNanos() >= retentionNanos) {
                events.removeFirst();
            }
            if (events.isEmpty()) {
                users.remove();
            }
        }
    }
}
