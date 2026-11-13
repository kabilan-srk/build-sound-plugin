package io.jenkins.plugins.buildsound;

import hudson.model.Result;
import java.util.Optional;

/** The two sounds the plugin can ask the browser to play. */
public enum Sound {
    SUCCESS,
    PROBLEM;

    /** Empty means "stay silent": the user stopped the build themselves, or it never ran. */
    public static Optional<Sound> fromResult(Result result) {
        if (result == Result.SUCCESS) {
            return Optional.of(SUCCESS);
        }
        if (result == Result.FAILURE || result == Result.UNSTABLE) {
            return Optional.of(PROBLEM);
        }
        return Optional.empty();
    }
}
