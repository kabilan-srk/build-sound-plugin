package io.jenkins.plugins.buildsound;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps the latest finished-build events for each user, in memory only.
 *
 * <p>Each user keeps at most {@code maxPerUser} events, and an event is dropped once it is older than the retention
 * time. One lock guards everything: it is simple, and the store is small and quick.
 */
public class EventStore {

    // One store for the whole Jenkins, shared by the listener and the endpoint.
    private static final EventStore INSTANCE = new EventStore(20, Duration.ofMinutes(5), Clock.systemUTC());

    public static EventStore instance() {
        return INSTANCE;
    }

    private final int maxPerUser;
    private final Duration retention;
    private final Clock clock;

    private final Object lock = new Object();
    // Everything below is only touched while holding "lock".
    private final Map<String, LinkedList<BuildEvent>> eventsByUser = new HashMap<>();
    private long lastId = 0;

    public EventStore(int maxPerUser, Duration retention, Clock clock) {
        this.maxPerUser = maxPerUser;
        this.retention = retention;
        this.clock = clock;
    }

    /** Stores an event for the user. Empty (and nothing stored) when the build has no real human user. */
    public Optional<BuildEvent> add(String userId, String jobFullName, int buildNumber, Sound sound) {
        if (!isRealUser(userId)) {
            return Optional.empty();
        }
        synchronized (lock) {
            long now = clock.millis();
            pruneExpired(now);
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

    /** The highest id among this user's own events, or 0 if there are none. */
    public long latestIdFor(String userId) {
        synchronized (lock) {
            pruneExpired(clock.millis());
            LinkedList<BuildEvent> events = eventsByUser.get(userId);
            return events == null || events.isEmpty() ? 0 : events.getLast().getId();
        }
    }

    /** This user's events with an id greater than {@code afterId}, oldest first. */
    public List<BuildEvent> eventsSince(String userId, long afterId) {
        synchronized (lock) {
            pruneExpired(clock.millis());
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

    /** For tests: forget everything. */
    public void clear() {
        synchronized (lock) {
            eventsByUser.clear();
        }
    }

    private static boolean isRealUser(String userId) {
        return userId != null && !userId.trim().isEmpty() && !"SYSTEM".equals(userId) && !"anonymous".equals(userId);
    }

    // Caller must hold "lock". Events are in time order, so we can stop at the first one that is still fresh.
    private void pruneExpired(long now) {
        long oldestAllowed = now - retention.toMillis();
        Iterator<LinkedList<BuildEvent>> users = eventsByUser.values().iterator();
        while (users.hasNext()) {
            LinkedList<BuildEvent> events = users.next();
            while (!events.isEmpty() && events.getFirst().getTimestampMillis() <= oldestAllowed) {
                events.removeFirst();
            }
            if (events.isEmpty()) {
                users.remove();
            }
        }
    }
}
