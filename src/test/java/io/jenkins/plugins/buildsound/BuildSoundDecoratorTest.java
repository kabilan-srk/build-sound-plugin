package io.jenkins.plugins.buildsound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.PluginWrapper;
import hudson.model.Item;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import jenkins.model.Jenkins;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BuildSoundDecoratorTest {

    /** Security shared by all tests. Called first in each test (the rule is only available there). */
    private static void configure(JenkinsRule j) {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        // Anonymous may read, so the main page really renders for anonymous and the missing script is our doing.
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ)
                .everywhere()
                .toEveryone()
                .grant(Item.READ)
                .everywhere()
                .toAuthenticated());
    }

    /** A web client that does not run the page's JavaScript: these tests only look at the HTML Jenkins sends. */
    private static JenkinsRule.WebClient client(JenkinsRule j, String user) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);
        wc.getOptions().setPrintContentOnFailingStatusCode(false);
        if (user != null) {
            wc.login(user);
        }
        return wc;
    }

    /** All {@code <script>} tags on the page that come from this plugin. */
    private static List<DomElement> pluginScripts(HtmlPage page) {
        List<DomElement> found = new ArrayList<>();
        for (DomElement script : page.getElementsByTagName("script")) {
            if (script.getAttribute("src").contains("/plugin/build-sound/")) {
                found.add(script);
            }
        }
        return found;
    }

    private static int status(JenkinsRule j, JenkinsRule.WebClient wc, String path) throws Exception {
        // The page uses root-relative URLs ("/jenkins/plugin/..."), so resolve them against the server address.
        return wc.getPage(new URL(j.getURL(), path)).getWebResponse().getStatusCode();
    }

    @Test
    void loggedInPageHasExternalScriptWithDataAttributes(JenkinsRule j) throws Exception {
        configure(j);
        HtmlPage page = client(j, "alice").goTo("");

        List<DomElement> scripts = pluginScripts(page);
        assertEquals(1, scripts.size());
        DomElement script = scripts.get(0);
        // Jenkins gives the root URL as the context path, for example "/jenkins" (empty if Jenkins is at the top).
        String root = j.getURL().getPath().replaceAll("/$", "");
        assertTrue(script.getAttribute("src").startsWith(root + "/plugin/build-sound/build-sound.js?v="));
        assertEquals(root, script.getAttribute("data-root-url"));
        assertEquals("alice", script.getAttribute("data-user-id"));
        assertEquals(root + "/plugin/build-sound", script.getAttribute("data-resource-url"));
        assertTrue(script.getAttribute("data-version").matches("[0-9a-f]{8}"));
        // The version in the attribute is the one in the script URL, so every URL changes on an upgrade.
        assertTrue(script.getAttribute("src").endsWith("?v=" + script.getAttribute("data-version")));
    }

    @Test
    void pageNeverContainsTheRawPluginVersion() {
        // A local snapshot version can contain the OS user name of whoever built the plugin.
        String version = "1.0-SNAPSHOT (private-abc123-someuser)";
        String code = BuildSoundDecorator.shortHash(version);
        assertTrue(code.matches("[0-9a-f]{8}"));
        assertFalse(code.contains("someuser"));
        assertEquals(code, BuildSoundDecorator.shortHash(version)); // the same version always gives the same code
        assertNotEquals(code, BuildSoundDecorator.shortHash("1.1")); // another version gives another code
    }

    @Test
    void pageDoesNotContainTheRawPluginVersion(JenkinsRule j) throws Exception {
        configure(j);
        String html = client(j, "alice").goTo("").getWebResponse().getContentAsString();
        PluginWrapper plugin = j.jenkins.getPluginManager().getPlugin("build-sound");
        assertNotNull(plugin, "the plugin under test should be loaded");
        String realVersion = plugin.getVersion();
        assertFalse(html.contains("?v=" + realVersion), "the raw version is in the page");
    }

    @Test
    void pluginAddsNoInlineScript(JenkinsRule j) throws Exception {
        configure(j);
        HtmlPage page = client(j, "alice").goTo("");
        for (DomElement script : page.getElementsByTagName("script")) {
            boolean inline = script.getAttribute("src").isEmpty();
            if (inline) {
                assertFalse(script.getTextContent().contains("build-sound"), "inline script mentions the plugin");
            }
        }
    }

    @Test
    void anonymousPageHasNoScript(JenkinsRule j) throws Exception {
        configure(j);
        HtmlPage page = client(j, null).goTo("");
        assertEquals(0, pluginScripts(page).size());
    }

    @Test
    void loginPageHasNoScript(JenkinsRule j) throws Exception {
        configure(j);
        HtmlPage page = client(j, null).goTo("login");
        assertEquals(0, pluginScripts(page).size());
    }

    @Test
    void loginPageHasNoScriptEvenWhenAlreadyLoggedIn(JenkinsRule j) throws Exception {
        configure(j);
        HtmlPage page = client(j, "alice").goTo("login");
        assertEquals(0, pluginScripts(page).size());
    }

    @Test
    void jobNamedLoginStillGetsTheScript(JenkinsRule j) throws Exception {
        configure(j);
        j.createFreeStyleProject("login");
        // The address of this page ends in "/login/", and an older check by address would have hidden the script.
        assertEquals(1, pluginScripts(client(j, "alice").goTo("job/login/")).size());
    }

    @Test
    void userIdIsEscapedInTheAttribute(JenkinsRule j) throws Exception {
        configure(j);
        String nasty = "a\"><b>x";
        JenkinsRule.WebClient wc = client(j, nasty);
        HtmlPage page = wc.goTo("");

        // The parsed attribute holds the exact id: the quote did not end it early...
        List<DomElement> scripts = pluginScripts(page);
        assertEquals(1, scripts.size());
        assertEquals(nasty, scripts.get(0).getAttribute("data-user-id"));
        // ...and the raw HTML never contains the unescaped text, so no tag was injected.
        assertFalse(page.getWebResponse().getContentAsString().contains("data-user-id=\"" + nasty));
    }

    @Test
    void scriptWorkerAndSoundsAreServed(JenkinsRule j) throws Exception {
        configure(j);
        JenkinsRule.WebClient wc = client(j, "alice");
        DomElement script = pluginScripts(wc.goTo("")).get(0);
        String version = script.getAttribute("data-version");
        String resources = script.getAttribute("data-resource-url");

        assertEquals(200, status(j, wc, script.getAttribute("src")));
        assertEquals(200, status(j, wc, resources + "/build-sound-worker.js?v=" + version));
        assertEquals(200, status(j, wc, resources + "/sounds/success.wav?v=" + version));
        assertEquals(200, status(j, wc, resources + "/sounds/problem.wav?v=" + version));
    }
}
