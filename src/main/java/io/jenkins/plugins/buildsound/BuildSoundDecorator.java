package io.jenkins.plugins.buildsound;

import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.PageDecorator;
import hudson.model.User;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Adds the Build Sound script to the bottom of Jenkins pages. Jenkins renders {@code footer.jelly} of every
 * PageDecorator on every page, so this class only decides whether this page should get the script.
 */
@Extension
public class BuildSoundDecorator extends PageDecorator {

    /** The id of the logged-in user, or null when nobody is logged in. */
    public String getUserId() {
        User user = User.current();
        return user == null ? null : user.getId();
    }

    /**
     * True only for a logged-in user on a normal page. Anonymous visitors cannot call the endpoint, and the
     * login page must stay free of extra scripts (even for someone who is already logged in).
     */
    public boolean isActive() {
        if (getUserId() == null) {
            return false;
        }
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request != null) {
            String path = request.getRequestURI();
            if (path != null && path.endsWith("/login")) {
                return false;
            }
        }
        return true;
    }

    /**
     * The plugin version. It is added to the script, worker and sound URLs so that after an upgrade the browser
     * fetches the new files instead of using its cached copies.
     */
    public String getVersion() {
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("build-sound");
        return plugin == null ? "dev" : plugin.getVersion();
    }
}
