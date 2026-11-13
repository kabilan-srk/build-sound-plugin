package io.jenkins.plugins.buildsound;

/** One finished build. Immutable (fields are final, no setters), so it is safe to share between threads. */
public class BuildEvent {

    private final long id;
    private final String jobFullName;
    private final int buildNumber;
    private final Sound sound;
    private final long timestampMillis;

    public BuildEvent(long id, String jobFullName, int buildNumber, Sound sound, long timestampMillis) {
        this.id = id;
        this.jobFullName = jobFullName;
        this.buildNumber = buildNumber;
        this.sound = sound;
        this.timestampMillis = timestampMillis;
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

    public long getTimestampMillis() {
        return timestampMillis;
    }
}
