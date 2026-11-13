package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.model.Item;
import java.net.URL;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.WebResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildSoundActionTest {

    @BeforeEach
    void setUp() {
        EventStore.instance().clear();
    }

    /** Security and jobs shared by all tests. Called first in each test (the rule is only available there). */
    private static void configure(JenkinsRule j) throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // Anonymous may reach Jenkins, so a 403 from us really comes from the plugin and not from Jenkins itself.
        // Logged-in users may read and build every job.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .toEveryone()
                .grant(Item.READ, Item.BUILD)
                .everywhere()
                .toAuthenticated());
        // The endpoint only returns events for jobs that exist, so the jobs used below must exist.
        j.createFreeStyleProject("job");
        j.createFreeStyleProject("other");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static JenkinsRule.WebClient client(JenkinsRule j, String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        if (user != null) {
            wc.login(user);
        }
        return wc;
    }

    private static WebResponse get(JenkinsRule.WebClient wc, String path) throws Exception {
        return wc.goTo(path, null).getWebResponse();
    }

    private static JSONObject json(WebResponse response) {
        return JSONObject.fromObject(response.getContentAsString());
    }

    private static BuildEvent add(String user, String job, int number, Sound sound) {
        return EventStore.instance().add(user, job, number, sound).get();
    }

    // ---- tests ---------------------------------------------------------------------------------------------

    @Test
    void anonymousGets403OnLatest(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        assertEquals(403, get(client(j, null), "build-sound/latest").getStatusCode());
    }

    @Test
    void anonymousGets403OnEvents(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        assertEquals(403, get(client(j, null), "build-sound/events?after=0").getStatusCode());
    }

    @Test
    void latestReturnsOwnLatestIdAndInstance(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        BuildEvent aliceLast = add("alice", "job", 2, Sound.PROBLEM);
        add("bob", "job", 3, Sound.SUCCESS); // newer, but Bob's: Alice must not learn about it

        WebResponse response = get(client(j, "alice"), "build-sound/latest");
        assertEquals(200, response.getStatusCode());
        JSONObject body = json(response);
        assertEquals(aliceLast.getId(), body.getLong("latest"));
        assertFalse(body.getString("instance").isEmpty());
    }

    @Test
    void latestIsZeroWhenUserHasNoEvents(JenkinsRule j) throws Exception {
        configure(j);
        add("bob", "job", 1, Sound.SUCCESS);
        assertEquals(0, json(get(client(j, "alice"), "build-sound/latest")).getLong("latest"));
    }

    @Test
    void eventsAfterCursorOnly(JenkinsRule j) throws Exception {
        configure(j);
        BuildEvent first = add("alice", "job", 1, Sound.SUCCESS);
        BuildEvent second = add("alice", "other", 7, Sound.PROBLEM);

        JSONObject body = json(get(client(j, "alice"), "build-sound/events?after=" + first.getId()));
        JSONArray events = body.getJSONArray("events");
        assertEquals(1, events.size());
        JSONObject event = events.getJSONObject(0);
        assertEquals(second.getId(), event.getLong("id"));
        assertEquals("other", event.getString("job"));
        assertEquals(7, event.getInt("number"));
        assertEquals("problem", event.getString("sound"));
        assertEquals(second.getId(), body.getLong("latest"));
    }

    @Test
    void successSoundIsLowerCase(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        JSONObject body = json(get(client(j, "alice"), "build-sound/events?after=0"));
        assertEquals("success", body.getJSONArray("events").getJSONObject(0).getString("sound"));
    }

    @Test
    void userSeesOnlyOwnEvents(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        add("bob", "other", 2, Sound.PROBLEM);

        JSONArray alice =
                json(get(client(j, "alice"), "build-sound/events?after=0")).getJSONArray("events");
        JSONArray bob =
                json(get(client(j, "bob"), "build-sound/events?after=0")).getJSONArray("events");
        assertEquals(1, alice.size());
        assertEquals("job", alice.getJSONObject(0).getString("job"));
        assertEquals(1, bob.size());
        assertEquals("other", bob.getJSONObject(0).getString("job"));
    }

    @Test
    void listenerAndEndpointAgreeOnUserId(JenkinsRule j) throws Exception {
        configure(j);
        // A real start through the web UI, so the listener records the id Jenkins gives the cause,
        // and the endpoint must find it with the id Jenkins gives the logged-in caller.
        JenkinsRule.WebClient wc = client(j, "alice");
        WebRequest start = new WebRequest(new URL(wc.getContextPath() + "job/job/build"), HttpMethod.POST);
        wc.addCrumb(start);
        wc.getPage(start);
        j.waitUntilNoActivity();

        JSONArray events = json(get(wc, "build-sound/events?after=0")).getJSONArray("events");
        assertEquals(1, events.size());
        assertEquals("job", events.getJSONObject(0).getString("job"));
        assertEquals("success", events.getJSONObject(0).getString("sound"));
    }

    @Test
    void badAfterParameterGives400(JenkinsRule j) throws Exception {
        configure(j);
        JenkinsRule.WebClient wc = client(j, "alice");
        assertEquals(400, get(wc, "build-sound/events").getStatusCode());
        assertEquals(400, get(wc, "build-sound/events?after=").getStatusCode());
        assertEquals(400, get(wc, "build-sound/events?after=abc").getStatusCode());
        assertEquals(400, get(wc, "build-sound/events?after=1.5").getStatusCode());
    }

    @Test
    void responsesAreNoStore(JenkinsRule j) throws Exception {
        configure(j);
        add("alice", "job", 1, Sound.SUCCESS);
        JenkinsRule.WebClient wc = client(j, "alice");
        assertEquals("no-store", get(wc, "build-sound/latest").getResponseHeaderValue("Cache-Control"));
        assertEquals("no-store", get(wc, "build-sound/events?after=0").getResponseHeaderValue("Cache-Control"));
    }

    @Test
    void instanceIdIsStableWithinOneRun(JenkinsRule j) throws Exception {
        configure(j);
        JenkinsRule.WebClient wc = client(j, "alice");
        String first = json(get(wc, "build-sound/latest")).getString("instance");
        String second = json(get(wc, "build-sound/latest")).getString("instance");
        String third = json(get(wc, "build-sound/events?after=0")).getString("instance");
        assertEquals(first, second);
        assertEquals(first, third);
        // Another user sees the same id: it identifies this Jenkins run, not a user.
        assertEquals(first, json(get(client(j, "bob"), "build-sound/latest")).getString("instance"));
    }
}
