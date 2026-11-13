package io.jenkins.plugins.buildsound;

import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.PageDecorator;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Adds the Build Sound script to the bottom of Jenkins pages. Jenkins renders {@code footer.jelly} of every
 * PageDecorator on every page, so this class only decides whether this page should get the script.
 */
@Extension
public class BuildSoundDecorator extends PageDecorator {

    /** The id of the logged-in user, or null when nobody is logged in. */
    @Restricted(NoExternalUse.class)
    public String getUserId() {
        return BuildSoundAction.currentUserId();
    }

    /**
     * Jelly reads this as {@code ${it.active}} (see footer.jelly): Jelly turns the property name into a call of
     * {@code isActive()}.
     *
     * <p>True only for a logged-in user. Anonymous visitors cannot call the endpoint. The login page needs no check
     * of its own: Jenkins does not render page decorators there (a test checks this, also for a logged-in user).
     */
    @Restricted(NoExternalUse.class)
    public boolean isActive() {
        return getUserId() != null;
    }

    /**
     * A short code for the plugin version. It is added to the script, worker and sound URLs so that after an
     * upgrade the browser fetches the new files instead of using its cached copies. It is a hash and not the
     * version itself, because a local snapshot version can contain the name of the user who built it.
     */
    @Restricted(NoExternalUse.class)
    public String getVersion() {
        PluginWrapper plugin = Jenkins.get().getPluginManager().getPlugin("build-sound");
        return shortHash(plugin == null ? "dev" : plugin.getVersion());
    }

    /** Eight hex digits that depend only on the text, so the same version always gives the same code. */
    static String shortHash(String text) {
        return String.format("%08x", text.hashCode());
    }
}
