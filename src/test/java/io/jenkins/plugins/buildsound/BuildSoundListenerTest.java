package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.tasks.BuildTrigger;
import hudson.triggers.TimerTrigger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.security.ApiTokenProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildSoundListenerTest {

    @BeforeEach
    void setUp() {
        BrokenUserCause.broken = false;
        EventStore.instance().clear();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static List<BuildEvent> eventsOf(String user) {
        return EventStore.instance().eventsSince(user, 0);
    }

    /** Stores a throwaway event for a probe user and returns its id; the ids of two calls tell how much was stored. */
    private static long nextEventId() {
        return EventStore.instance().add("probe", "job", 1, Sound.SUCCESS).get().getId();
    }

    /** When the build finished, in milliseconds. */
    private static long endOf(Run<?, ?> run) {
        return run.getStartTimeInMillis() + run.getDuration();
    }

    /** The "somebody waits for me" marker the Pipeline build step puts on a downstream run, or null. */
    private static Action waitMarkerOf(Run<?, ?> run) {
        for (Action action : run.getAllActions()) {
            if (action.getClass().getName().endsWith(".BuildTriggerAction")) {
                return action;
            }
        }
        return null;
    }

    /** Waits (at most one minute) until the job's last build has the wait marker, and returns the marker. */
    private static Action waitForMarkerOnLastBuildOf(JenkinsRule j, String jobName) throws Exception {
        WorkflowJob job = j.jenkins.getItemByFullName(jobName, WorkflowJob.class);
        long giveUp = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < giveUp) {
            WorkflowRun run = job.getLastBuild();
            if (run != null && waitMarkerOf(run) != null) {
                return waitMarkerOf(run);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no wait marker on " + jobName);
    }

    /** The last build of a job, once it exists and has finished (waits at most one minute for it to appear). */
    private static WorkflowRun finishedBuildOf(JenkinsRule j, String jobName) throws Exception {
        WorkflowJob job = j.jenkins.getItemByFullName(jobName, WorkflowJob.class);
        long giveUp = System.currentTimeMillis() + 60_000;
        while (job.getLastBuild() == null && System.currentTimeMillis() < giveUp) {
            Thread.sleep(200);
        }
        WorkflowRun run = job.getLastBuild();
        j.waitForCompletion(run);
        return run;
    }

    private static CauseAction startedBy(String user) {
        return new CauseAction(new Cause.UserIdCause(user));
    }

    private static WorkflowJob pipeline(JenkinsRule j, String name, String script) throws Exception {
        WorkflowJob job = j.createProject(WorkflowJob.class, name);
        job.setDefinition(new CpsFlowDefinition(script, true));
        return job;
    }

    /** Real security, so that API tokens and replay behave like on a real Jenkins. */
    private static void enableSecurity(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
    }

    /** A build that sets its own result, so tests can make an aborted build without stopping a real one. */
    private static class SetResult extends TestBuilder {
        private final Result result;

        SetResult(Result result) {
            this.result = result;
        }

        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
            build.setResult(result);
            return true;
        }
    }

    /**
     * A user cause that blows up when asked who the user is, but only once the build has run. Jenkins itself reads
     * the id when the build starts, so it must work until then; only the listener asks afterwards.
     */
    private static class BrokenUserCause extends Cause.UserIdCause {
        static volatile boolean broken = false;

        BrokenUserCause() {
            super("alice");
        }

        @Override
        public String getUserId() {
            if (broken) {
                throw new IllegalStateException("broken on purpose");
            }
            return super.getUserId();
        }
    }

    // ---- basic rules ---------------------------------------------------------------------------------------

    @Test
    void userStartedSuccessCreatesEvent(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.scheduleBuild2(0, startedBy("alice")).get();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals("job", events.get(0).getJobFullName());
        assertEquals(1, events.get(0).getBuildNumber());
        assertEquals(Sound.SUCCESS, events.get(0).getSound());
    }

    @Test
    void failedBuildCreatesProblemEvent(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.getBuildersList().add(new FailureBuilder());
        p.scheduleBuild2(0, startedBy("alice")).get();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals(Sound.PROBLEM, events.get(0).getSound());
    }

    @Test
    void buildWithoutUserCauseCreatesNoEvent(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        // The store hands out ids one by one, whatever user an event is for. So if the builds below stored
        // anything (even under an odd id like "SYSTEM"), the next id would skip a number.
        long idBefore = nextEventId();

        p.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()).get();
        p.scheduleBuild2(0, new Cause.RemoteCause("1.2.3.4", "note")).get();
        p.scheduleBuild2(0).get();

        assertEquals(idBefore + 1, nextEventId(), "a build without a user stored an event");
        assertTrue(eventsOf("alice").isEmpty());
    }

    @Test
    void abortedBuildCreatesNoEvent(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.getBuildersList().add(new SetResult(Result.ABORTED));
        FreeStyleBuild build = p.scheduleBuild2(0, startedBy("alice")).get();

        assertEquals(Result.ABORTED, build.getResult());
        assertTrue(eventsOf("alice").isEmpty());
    }

    @Test
    void twoUsersEachGetOnlyTheirOwnEvent(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.scheduleBuild2(0, startedBy("alice")).get();
        p.scheduleBuild2(0, startedBy("bob")).get();

        List<BuildEvent> alice = eventsOf("alice");
        List<BuildEvent> bob = eventsOf("bob");
        assertEquals(1, alice.size());
        assertEquals(1, bob.size());
        assertEquals(1, alice.get(0).getBuildNumber());
        assertEquals(2, bob.get(0).getBuildNumber());
    }

    @Test
    void eventUsesFullNameForFolderJob(JenkinsRule j) throws Exception {
        MockFolder folder = j.jenkins.createProject(MockFolder.class, "folder");
        FreeStyleProject p = folder.createProject(FreeStyleProject.class, "job");
        p.scheduleBuild2(0, startedBy("alice")).get();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals("folder/job", events.get(0).getJobFullName());
    }

    @Test
    void listenerErrorIsCaughtAndLogged(JenkinsRule j) throws Exception {
        // Jenkins core also swallows exceptions from listeners, so a finished build could never show this. We call
        // the listener ourselves: its own catch must stop the error, and the plugin must log a warning.
        FreeStyleProject p = j.createFreeStyleProject("job");
        FreeStyleBuild build = p.scheduleBuild2(0, new BrokenUserCause()).get();
        EventStore.instance().clear();
        BrokenUserCause.broken = true;

        List<LogRecord> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger(BuildSoundListener.class.getName());
        logger.addHandler(handler);
        try {
            assertDoesNotThrow(() -> new BuildSoundListener().onCompleted(build, TaskListener.NULL));
        } finally {
            logger.removeHandler(handler);
        }

        assertTrue(eventsOf("alice").isEmpty());
        assertEquals(1, logged.size());
        assertEquals(Level.WARNING, logged.get(0).getLevel());
    }

    // ---- who started the build -----------------------------------------------------------------------------

    @Test
    void apiTokenStartRecordsForTheTokensUser(JenkinsRule j) throws Exception {
        enableSecurity(j);
        FreeStyleProject p = j.createFreeStyleProject("job");
        User alice = User.getOrCreateByIdOrFullName("alice");
        String token = alice.getProperty(ApiTokenProperty.class).generateNewToken("test").plainValue;

        HttpURLConnection connection = (HttpURLConnection) new URL(j.getURL(), "job/job/build").openConnection();
        connection.setRequestMethod("POST");
        String login = Base64.getEncoder().encodeToString(("alice:" + token).getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Authorization", "Basic " + login);
        assertEquals(201, connection.getResponseCode());
        j.waitUntilNoActivity();

        assertEquals(1, p.getBuilds().size());
        assertEquals(1, eventsOf("alice").size());
    }

    @Test
    void replayByBobRecordsForBobOnly(JenkinsRule j) throws Exception {
        enableSecurity(j);
        WorkflowJob p = pipeline(j, "pipe", "echo 'hi'");
        WorkflowRun original = p.scheduleBuild2(0, startedBy("alice")).get();
        assertEquals(1, eventsOf("alice").size());

        ReplayAction replay = original.getAction(ReplayAction.class);
        try (ACLContext ignored = ACL.as2(User.getOrCreateByIdOrFullName("bob").impersonate2())) {
            replay.run2(replay.getOriginalScript(), Collections.emptyMap());
        }
        j.waitUntilNoActivity();

        List<BuildEvent> bob = eventsOf("bob");
        assertEquals(1, bob.size());
        assertEquals(2, bob.get(0).getBuildNumber());
        // Alice still has only her own original build.
        assertEquals(1, eventsOf("alice").size());
    }

    @Test
    void twoDirectStartersOnOneBuildBothGetTheEvent(JenkinsRule j) throws Exception {
        // What a merged queue item looks like: one build whose causes name two users. Built by hand, so the test
        // does not depend on timing.
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.scheduleBuild2(0, new CauseAction(new Cause.UserIdCause("alice"), new Cause.UserIdCause("bob")))
                .get();

        assertEquals(1, p.getBuilds().size());
        assertEquals(1, eventsOf("alice").size());
        assertEquals(1, eventsOf("bob").size());
    }

    // ---- downstream builds ---------------------------------------------------------------------------------

    @Test
    void freestyleDownstreamAfterUpstreamFinishedRecordsForAlice(JenkinsRule j) throws Exception {
        FreeStyleProject a = j.createFreeStyleProject("A");
        j.createFreeStyleProject("B");
        a.getPublishersList().add(new BuildTrigger("B", Result.SUCCESS));
        j.jenkins.rebuildDependencyGraph();

        a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(2, events.size());
        // B is not waited for (A has no wait marker), so it records like any other build.
        assertEquals("A", events.get(0).getJobFullName());
        assertEquals("B", events.get(1).getJobFullName());
    }

    @Test
    void pipelineWaitingForDownstreamSuccessIsSilentForDownstream(JenkinsRule j) throws Exception {
        pipeline(j, "B", "sleep 1");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'");
        a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals("A", events.get(0).getJobFullName());
        assertEquals(Sound.SUCCESS, events.get(0).getSound());
    }

    @Test
    void pipelineWaitingForDownstreamFailureRecordsProblemForDownstream(JenkinsRule j) throws Exception {
        pipeline(j, "B", "sleep 1\nerror 'boom'");
        WorkflowJob a = pipeline(j, "A", "build job: 'B', wait: true, propagate: false");
        a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(2, events.size());
        assertEquals("B", events.get(0).getJobFullName());
        assertEquals(Sound.PROBLEM, events.get(0).getSound());
        assertEquals("A", events.get(1).getJobFullName());
        assertEquals(Sound.SUCCESS, events.get(1).getSound());
    }

    @Test
    void pipelineNotWaitingWhileUpstreamStillRunsRecordsForDownstream(JenkinsRule j) throws Exception {
        pipeline(j, "B", "sleep 1");
        // A is still sleeping when B completes, but it did not wait for B, so B must play on its own.
        WorkflowJob a = pipeline(j, "A", "build job: 'B', wait: false, quietPeriod: 0\nsleep 20");
        WorkflowRun aRun = a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        // The case this test is about: B was already done while A still ran.
        WorkflowRun bRun = finishedBuildOf(j, "B");
        assertTrue(endOf(bRun) < endOf(aRun), "B must finish before A, or the test checks nothing");

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(2, events.size());
        assertEquals("B", events.get(0).getJobFullName());
        assertEquals("A", events.get(1).getJobFullName());
    }

    @Test
    void waitingUpstreamThatHasFinishedDoesNotSilenceDownstream(JenkinsRule j) throws Exception {
        // A waits for B, so B carries the wait marker while it runs. Take the marker while B runs...
        pipeline(j, "B", "sleep 4");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'");
        a.scheduleBuild2(0, startedBy("alice"));
        Action marker = waitForMarkerOnLastBuildOf(j, "B");
        j.waitUntilNoActivity();
        WorkflowRun bRun = finishedBuildOf(j, "B");
        assertEquals(1, eventsOf("alice").size(), "while A waited, only A played");
        EventStore.instance().clear();

        // ...Jenkins removes the marker when B is finalized, so put it back and tell the listener about B again.
        // The marker is there, but A is finished and nobody waits any more: the isBuilding() check makes B play.
        bRun.addAction(marker);
        new BuildSoundListener().onCompleted(bRun, TaskListener.NULL);

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals("B", events.get(0).getJobFullName());
    }

    @Test
    void onlyTheDirectUpstreamDecidesWaitedFor(JenkinsRule j) throws Exception {
        // A waits for B. B starts C without waiting. C ends while A still runs.
        // C is not waited for by B, so C plays. B is waited for by A, so B is silent. A plays last.
        pipeline(j, "C", "sleep 3");
        pipeline(j, "B", "build job: 'C', wait: false, quietPeriod: 0");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'\nsleep 25");
        WorkflowRun aRun = a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        WorkflowRun cRun = finishedBuildOf(j, "C");
        assertTrue(endOf(cRun) < endOf(aRun), "C must finish while A still runs, or the test checks nothing");

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(2, events.size());
        assertEquals("C", events.get(0).getJobFullName());
        assertEquals(Sound.SUCCESS, events.get(0).getSound());
        assertEquals("A", events.get(1).getJobFullName());
    }

    @Test
    void upstreamLookupWorksWhenTheBuildRunsAsAUserWhoCannotReadUpstream(JenkinsRule j) throws Exception {
        // A waits for B and keeps running after B ends (it sleeps), so B is "waited for" and silent. We grab the
        // wait marker while B runs, because Jenkins removes it when B is finalized.
        pipeline(j, "B", "sleep 2");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'\nsleep 60");
        a.scheduleBuild2(0, startedBy("alice"));
        Action marker = waitForMarkerOnLastBuildOf(j, "B");
        WorkflowRun bRun = finishedBuildOf(j, "B");
        assertTrue(eventsOf("alice").isEmpty(), "B is waited for, so it is silent");
        assertTrue(a.getLastBuild().isBuilding(), "A must still be running");
        bRun.addAction(marker);

        // Now a build identity that may enter Jenkins but may not read any job (this is what a build gets
        // when a QueueItemAuthenticator runs it as a restricted user).
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone());
        Cause.UpstreamCause cause = bRun.getCause(Cause.UpstreamCause.class);
        try (ACLContext ignored =
                ACL.as2(User.getOrCreateByIdOrFullName("carol").impersonate2())) {
            // The premise: the permission-checked lookup really finds nothing for this identity.
            assertNull(cause.getUpstreamRun());
            new BuildSoundListener().onCompleted(bRun, TaskListener.NULL);
        }

        assertTrue(eventsOf("alice").isEmpty(), "B is still waited for, whoever the build runs as");
    }

    @Test
    void notWaitedPathWinsOverWaitedPathForTheSameUser() {
        Set<String> notWaited = new LinkedHashSet<>();
        Set<String> waited = new LinkedHashSet<>();
        BuildSoundListener listener = new BuildSoundListener();
        // The same user reaches the build twice: once through a build that waits, once directly.
        listener.collectUsers(new Cause.UserIdCause("alice"), true, 0, notWaited, waited);
        listener.collectUsers(new Cause.UserIdCause("alice"), false, 0, notWaited, waited);

        // A success is silent only when every path was a waited one.
        assertEquals(Collections.singleton("alice"), BuildSoundListener.usersToTell(notWaited, waited, Sound.SUCCESS));
    }

    @Test
    void waitedOnlyUserHearsProblemButNotSuccess() {
        Set<String> notWaited = new LinkedHashSet<>();
        Set<String> waited = new LinkedHashSet<>();
        new BuildSoundListener().collectUsers(new Cause.UserIdCause("alice"), true, 0, notWaited, waited);

        assertTrue(
                BuildSoundListener.usersToTell(notWaited, waited, Sound.SUCCESS).isEmpty());
        assertEquals(Collections.singleton("alice"), BuildSoundListener.usersToTell(notWaited, waited, Sound.PROBLEM));
    }

    @Test
    void threeLevelWaitingChainRecordsOnlyForTheTop(JenkinsRule j) throws Exception {
        pipeline(j, "C", "sleep 1");
        pipeline(j, "B", "build job: 'C'");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'");
        a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(1, events.size());
        assertEquals("A", events.get(0).getJobFullName());
    }

    @Test
    void buildWithNoUserAnywhereInItsCausesRecordsNothing(JenkinsRule j) throws Exception {
        long idBefore = nextEventId();
        // Upstream started by a timer, downstream is waited for: no user at any depth.
        pipeline(j, "B", "sleep 1");
        WorkflowJob a = pipeline(j, "A", "build job: 'B'");
        a.scheduleBuild2(0, new CauseAction(new TimerTrigger.TimerTriggerCause()))
                .get();
        j.waitUntilNoActivity();

        // Both pipelines really ran.
        assertEquals(
                1,
                j.jenkins.getItemByFullName("A", WorkflowJob.class).getBuilds().size());
        assertEquals(
                1,
                j.jenkins.getItemByFullName("B", WorkflowJob.class).getBuilds().size());
        assertEquals(idBefore + 1, nextEventId(), "nothing may be stored for any user");
    }
}
