package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.Result;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventStoreTest {

    /** A clock the test can move forward by hand. */
    private static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private TestClock clock;
    private EventStore store;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        store = new EventStore(20, Duration.ofMinutes(5), clock);
        store.clear();
    }

    private BuildEvent addFor(String user) {
        return store.add(user, "job", 1, Sound.SUCCESS).get();
    }

    @Test
    void idsIncreaseAcrossUsers() {
        long first = addFor("alice").getId();
        long second = addFor("bob").getId();
        long third = addFor("alice").getId();
        assertTrue(first < second);
        assertTrue(second < third);
    }

    @Test
    void eventsAreIsolatedPerUser() {
        addFor("alice");
        addFor("bob");
        List<BuildEvent> events = store.eventsSince("alice", 0);
        assertEquals(1, events.size());
        assertEquals(1, store.eventsSince("bob", 0).size());
    }

    @Test
    void keepsOnlyLastTwentyPerUser() {
        for (int i = 0; i < 25; i++) {
            store.add("alice", "job", i, Sound.SUCCESS);
        }
        List<BuildEvent> events = store.eventsSince("alice", 0);
        assertEquals(20, events.size());
        // The oldest five were dropped, so the first kept one is build 5.
        assertEquals(5, events.get(0).getBuildNumber());
        assertEquals(24, events.get(19).getBuildNumber());
    }

    @Test
    void expiresAfterFiveMinutes() {
        addFor("alice");
        clock.advance(Duration.ofMinutes(5));
        assertTrue(store.eventsSince("alice", 0).isEmpty());
    }

    @Test
    void notExpiredJustBeforeFiveMinutes() {
        addFor("alice");
        clock.advance(Duration.ofMinutes(5).minusMillis(1));
        assertEquals(1, store.eventsSince("alice", 0).size());
    }

    @Test
    void addPrunesExpiredEventsOfAllUsers() {
        addFor("alice");
        clock.advance(Duration.ofMinutes(6));
        // Bob's add must clean up Alice's old event, even though Alice never reads.
        addFor("bob");
        assertTrue(store.eventsSince("alice", 0).isEmpty());
    }

    @Test
    void eventsSinceReturnsOnlyNewer() {
        long first = addFor("alice").getId();
        long second = addFor("alice").getId();
        List<BuildEvent> events = store.eventsSince("alice", first);
        assertEquals(1, events.size());
        assertEquals(second, events.get(0).getId());
    }

    @Test
    void emptyWhenUserHasNoEvents() {
        assertTrue(store.eventsSince("nobody", 0).isEmpty());
    }

    @Test
    void blankSystemAndAnonymousUsersAreNotStored() {
        for (String user : new String[] {null, "", "  ", "SYSTEM", "anonymous"}) {
            assertFalse(store.add(user, "job", 1, Sound.SUCCESS).isPresent(), "user: " + user);
        }
        assertTrue(store.eventsSince("SYSTEM", 0).isEmpty());
        assertTrue(store.eventsSince("anonymous", 0).isEmpty());
    }

    @Test
    void concurrentAddsGiveUniqueIdsAndLoseNothing() throws Exception {
        int threads = 8;
        int perThread = 100;
        // Big limit so nothing is dropped for being over the cap.
        EventStore big = new EventStore(threads * perThread, Duration.ofMinutes(5), clock);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String user = "user" + t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    big.add(user, "job", i, Sound.SUCCESS);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        Set<Long> ids = new HashSet<>();
        for (int t = 0; t < threads; t++) {
            List<BuildEvent> events = big.eventsSince("user" + t, 0);
            assertEquals(perThread, events.size());
            for (BuildEvent event : events) {
                ids.add(event.getId());
            }
        }
        assertEquals(threads * perThread, ids.size());
    }

    @Test
    void soundMapping() {
        assertEquals(Optional.of(Sound.SUCCESS), Sound.fromResult(Result.SUCCESS));
        assertEquals(Optional.of(Sound.PROBLEM), Sound.fromResult(Result.FAILURE));
        assertEquals(Optional.of(Sound.PROBLEM), Sound.fromResult(Result.UNSTABLE));
        assertEquals(Optional.empty(), Sound.fromResult(Result.ABORTED));
        assertEquals(Optional.empty(), Sound.fromResult(Result.NOT_BUILT));
    }
}
