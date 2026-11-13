package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.security.ACLContext;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildSoundActionPermissionTest {

    @BeforeEach
    void setUp() {
        EventStore.instance().clear();
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Everybody may enter Jenkins; item permissions are added by each test. */
    private static MockAuthorizationStrategy baseRules() {
        return new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().toEveryone();
    }

    private static void useSecurity(JenkinsRule j, MockAuthorizationStrategy rules) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(rules);
    }

    private static JSONObject eventsFor(JenkinsRule j, String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        wc.login(user);
        WebResponse response = wc.goTo("build-sound/events?after=0", null).getWebResponse();
        assertEquals(200, response.getStatusCode());
        return JSONObject.fromObject(response.getContentAsString());
    }

    private static void add(String user, String job, int number) {
        EventStore.instance().add(user, job, number, Sound.SUCCESS);
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void dropsEventWhenCallerLacksItemRead(JenkinsRule j) throws Exception {
        FreeStyleProject open = j.createFreeStyleProject("open");
        j.createFreeStyleProject("secret");
        useSecurity(j, baseRules().grant(Item.READ).onItems(open).to("bob"));
        add("bob", "secret", 1);
        add("bob", "open", 2);

        JSONArray events = eventsFor(j, "bob").getJSONArray("events");
        assertEquals(1, events.size());
        assertEquals("open", events.getJSONObject(0).getString("job"));
    }

    @Test
    void checksPermissionAsCallerNotSystem(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("secret");
        useSecurity(j, baseRules()); // nobody has Item.READ
        BuildEvent hidden =
                EventStore.instance().add("bob", "secret", 1, Sound.SUCCESS).get();

        // Premise: the system can see the job. If the endpoint checked as the system, Bob would get the event.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            assertNotNull(j.jenkins.getItemByFullName("secret"));
        }
        JSONObject body = eventsFor(j, "bob");
        assertEquals(0, body.getJSONArray("events").size());
        // A hidden build must not move the cursor, or the caller could tell that some build finished.
        assertEquals(0, body.getLong("latest"));
        assertTrue(hidden.getId() > 0);
    }

    @Test
    void hiddenEventDoesNotMoveTheCursorAfterAReadableOne(JenkinsRule j) throws Exception {
        FreeStyleProject open = j.createFreeStyleProject("open");
        j.createFreeStyleProject("secret");
        useSecurity(j, baseRules().grant(Item.READ).onItems(open).to("bob"));
        BuildEvent readable =
                EventStore.instance().add("bob", "open", 1, Sound.SUCCESS).get();
        EventStore.instance().add("bob", "secret", 2, Sound.SUCCESS);

        JSONObject body = eventsFor(j, "bob");
        assertEquals(1, body.getJSONArray("events").size());
        assertEquals(readable.getId(), body.getLong("latest"));
    }

    @Test
    void cursorStaysWhereItWasWhenOnlyHiddenEventsAreNewer(JenkinsRule j) throws Exception {
        j.createFreeStyleProject("secret");
        useSecurity(j, baseRules());
        EventStore.instance().add("bob", "secret", 1, Sound.SUCCESS);
        BuildEvent second =
                EventStore.instance().add("bob", "secret", 2, Sound.SUCCESS).get();
        long cursor = second.getId() - 1;

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("bob");
        WebResponse response =
                wc.goTo("build-sound/events?after=" + cursor, null).getWebResponse();
        JSONObject body = JSONObject.fromObject(response.getContentAsString());
        assertEquals(0, body.getJSONArray("events").size());
        assertEquals(cursor, body.getLong("latest"));
    }

    @Test
    void latestEndpointIgnoresHiddenEvents(JenkinsRule j) throws Exception {
        FreeStyleProject open = j.createFreeStyleProject("open");
        j.createFreeStyleProject("secret");
        useSecurity(j, baseRules().grant(Item.READ).onItems(open).to("bob"));
        BuildEvent readable =
                EventStore.instance().add("bob", "open", 1, Sound.SUCCESS).get();
        EventStore.instance().add("bob", "secret", 2, Sound.SUCCESS);

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login("bob");
        JSONObject body = JSONObject.fromObject(
                wc.goTo("build-sound/latest", null).getWebResponse().getContentAsString());
        assertEquals(readable.getId(), body.getLong("latest"));
    }

    @Test
    void dropsEventWhenJobWasDeleted(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("gone");
        FreeStyleProject kept = j.createFreeStyleProject("kept");
        useSecurity(j, baseRules().grant(Item.READ).everywhere().toAuthenticated());
        add("bob", "gone", 1);
        add("bob", "kept", 2);
        job.delete();

        JSONArray events = eventsFor(j, "bob").getJSONArray("events");
        assertEquals(1, events.size());
        assertEquals(kept.getFullName(), events.getJSONObject(0).getString("job"));
    }

    @Test
    void folderJobNameRoundTrips(JenkinsRule j) throws Exception {
        MockFolder folder = j.jenkins.createProject(MockFolder.class, "folder");
        folder.createProject(FreeStyleProject.class, "open");
        folder.createProject(FreeStyleProject.class, "closed");
        // Bob may read the folder and "open" inside it, but not "closed".
        useSecurity(
                j, baseRules().grant(Item.READ).onPaths("folder", "folder/open").to("bob"));
        add("bob", "folder/closed", 1);
        add("bob", "folder/open", 2);

        JSONArray events = eventsFor(j, "bob").getJSONArray("events");
        assertEquals(1, events.size());
        assertEquals("folder/open", events.getJSONObject(0).getString("job"));
    }

    @Test
    void userLosingAccessLaterStopsGettingNames(JenkinsRule j) throws Exception {
        FreeStyleProject job = j.createFreeStyleProject("job");
        useSecurity(j, baseRules().grant(Item.READ).onItems(job).to("bob"));
        add("bob", "job", 1);
        assertEquals(1, eventsFor(j, "bob").getJSONArray("events").size());

        // Access is taken away after the event was recorded.
        useSecurity(j, baseRules());
        assertEquals(0, eventsFor(j, "bob").getJSONArray("events").size());
    }
}
