package io.jenkins.plugins.buildsound;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** One finished build. Immutable (fields are final, no setters), so it is safe to share between threads. */
@Restricted(NoExternalUse.class)
public class BuildEvent {

    private final long id;
    private final String jobFullName;
    private final int buildNumber;
    private final Sound sound;
    private final long createdNanos; // reading of the store's clock when the event was made; only for expiry

    public BuildEvent(long id, String jobFullName, int buildNumber, Sound sound, long createdNanos) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.sound = sound;
        this.createdNanos = createdNanos;
    }

    public long getId() {
        return id;
    }

    public String getJobFullName() {
        return jobFullName;
    }

    public int getBuildNumber() {
        return buildNumber;
    }

    public Sound getSound() {
        return sound;
    }

    public long getCreatedNanos() {
        return createdNanos;
    }
}
