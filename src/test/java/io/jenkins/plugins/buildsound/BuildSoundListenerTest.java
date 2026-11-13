package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.tasks.BuildTrigger;
import hudson.triggers.TimerTrigger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import jenkins.security.ApiTokenProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.JenkinsRule;
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
        p.scheduleBuild2(0, new TimerTrigger.TimerTriggerCause()).get();
        p.scheduleBuild2(0, new Cause.RemoteCause("1.2.3.4", "note")).get();
        p.scheduleBuild2(0).get();

        // Nobody is a user here, so the store must be empty for every id we could ask about.
        assertTrue(eventsOf("alice").isEmpty());
        assertTrue(eventsOf("SYSTEM").isEmpty());
        assertTrue(eventsOf("anonymous").isEmpty());
        assertEquals(0, EventStore.instance().latestIdFor("alice"));
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
    void listenerErrorDoesNotFailTheBuild(JenkinsRule j) throws Exception {
        FreeStyleProject p = j.createFreeStyleProject("job");
        // After the build step runs, getUserId() throws. The build must still finish normally.
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
                BrokenUserCause.broken = true;
                return true;
            }
        });
        FreeStyleBuild build = p.scheduleBuild2(0, new BrokenUserCause()).get();

        assertEquals(Result.SUCCESS, build.getResult());
        assertTrue(eventsOf("alice").isEmpty());
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
        // A real queue merge: two starts inside the quiet period fold into one build with two user causes.
        FreeStyleProject p = j.createFreeStyleProject("job");
        p.scheduleBuild2(3, startedBy("alice"));
        p.scheduleBuild2(3, startedBy("bob"));
        j.waitUntilNoActivity();

        assertEquals(1, p.getBuilds().size(), "the two starts should have merged into one build");
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
        a.scheduleBuild2(0, startedBy("alice")).get();
        j.waitUntilNoActivity();

        List<BuildEvent> events = eventsOf("alice");
        assertEquals(2, events.size());
        assertEquals("B", events.get(0).getJobFullName());
        assertEquals("A", events.get(1).getJobFullName());
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
        assertTrue(eventsOf("alice").isEmpty());
        assertEquals(0, EventStore.instance().latestIdFor("alice"));
    }
}
