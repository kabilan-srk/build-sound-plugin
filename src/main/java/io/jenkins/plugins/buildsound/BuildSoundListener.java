package io.jenkins.plugins.buildsound;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records an event for each user a finished build belongs to. The rules for "belongs to" are in the design document,
 * section "Who a build belongs to".
 */
@Extension
public class BuildSoundListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(BuildSoundListener.class.getName());

    // Stops a loop or an absurdly deep chain of upstream causes from running forever.
    private static final int MAX_DEPTH = 10;

    // The Pipeline "build" step adds this action to a downstream build only when the upstream waits for it.
    // We compare the class name as text so this plugin does not need pipeline-build-step to be installed.
    private static final String WAIT_MARKER_CLASS =
            "org.jenkinsci.plugins.workflow.support.steps.build.BuildTriggerAction";

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        // A problem in this plugin must never make somebody's build fail, so catch everything and just log it.
        try {
            recordEvents(run);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Build Sound could not record an event for " + run.getFullDisplayName(), e);
        }
    }

    private void recordEvents(Run<?, ?> run) {
        Optional<Sound> sound = Sound.fromResult(run.getResult());
        if (!sound.isPresent()) {
            return; // aborted or not built: stay silent
        }

        // user id -> true if the user reached this build by at least one path where nobody waits for it.
        // A map gives us "at most one event per user per build" for free.
        Map<String, Boolean> users = new LinkedHashMap<>();
        for (Cause cause : run.getCauses()) {
            collectUsers(run, cause, false, 0, users);
        }

        String jobName = run.getParent().getFullName();
        for (Map.Entry<String, Boolean> entry : users.entrySet()) {
            boolean reachedWithoutWaiting = entry.getValue();
            // A waited-for build that succeeded stays silent: the upstream build plays its own sound later.
            // A waited-for build that did not succeed still plays, so a hidden failure is heard.
            if (reachedWithoutWaiting || sound.get() == Sound.PROBLEM) {
                EventStore.instance().add(entry.getKey(), jobName, run.getNumber(), sound.get());
            }
        }
    }

    /**
     * Adds the users found in {@code cause} to {@code users}.
     *
     * @param run the build whose cause this is (used to check whether somebody waits for it)
     * @param waited true if a build further down this path is already waited for by its upstream
     */
    private void collectUsers(Run<?, ?> run, Cause cause, boolean waited, int depth, Map<String, Boolean> users) {
        if (cause instanceof Cause.UserIdCause) {
            String userId = ((Cause.UserIdCause) cause).getUserId();
            if (userId != null) {
                boolean alreadyWithoutWaiting = Boolean.TRUE.equals(users.get(userId));
                // A path with no waiting wins over a waiting path.
                users.put(userId, alreadyWithoutWaiting || !waited);
            }
            return;
        }

        if (cause instanceof Cause.UpstreamCause && depth < MAX_DEPTH) {
            Cause.UpstreamCause upstream = (Cause.UpstreamCause) cause;
            boolean waitedNow = waited || isWaitedFor(run, upstream);
            // The upstream run can be null if it was deleted; then the next hop cannot be checked and counts as "not
            // waited".
            Run<?, ?> upstreamRun = upstream.getUpstreamRun();
            for (Cause nested : upstream.getUpstreamCauses()) {
                collectUsers(upstreamRun, nested, waitedNow, depth + 1, users);
            }
        }
    }

    /** True if the upstream build is still running and waiting for this build to finish. */
    private boolean isWaitedFor(Run<?, ?> run, Cause.UpstreamCause upstream) {
        if (run == null) {
            return false;
        }
        boolean hasMarker = false;
        for (Action action : run.getAllActions()) {
            if (WAIT_MARKER_CLASS.equals(action.getClass().getName())) {
                hasMarker = true;
                break;
            }
        }
        if (!hasMarker) {
            return false;
        }
        Run<?, ?> upstreamRun = upstream.getUpstreamRun();
        return upstreamRun != null && upstreamRun.isBuilding();
    }
}
