package io.jenkins.plugins.buildsound;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
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
        // Jenkins itself also catches errors thrown by listeners. This catch is a second safety net, so a problem
        // in this plugin is logged here, with our name on it, and can never fail somebody's build.
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

        // A set holds each user once, so a user gets at most one event per build.
        Set<String> notWaitedUsers = new LinkedHashSet<>(); // reached by a path where nobody waits for this build
        Set<String> waitedUsers = new LinkedHashSet<>(); // reached only by paths where the upstream waits
        // Looking up an upstream run is checked against the current user's permissions. A build can run as a
        // restricted user, who may not read the upstream job, and then the lookup would wrongly find nothing.
        // This is safe here: the listener only decides who is told. The endpoint still checks the caller's own
        // permission before it shows a job name.
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Cause cause : run.getCauses()) {
                boolean waited = false;
                if (cause instanceof Cause.UpstreamCause) {
                    // Only the direct upstream decides "waited for". Causes further up are only used to find users.
                    Run<?, ?> upstreamRun = ((Cause.UpstreamCause) cause).getUpstreamRun();
                    waited = isWaitedFor(run, upstreamRun);
                }
                collectUsers(cause, waited, 0, notWaitedUsers, waitedUsers);
            }
        }

        String jobName = run.getParent().getFullName();
        for (String userId : usersToTell(notWaitedUsers, waitedUsers, sound.get())) {
            EventStore.instance().add(userId, jobName, run.getNumber(), sound.get());
        }
    }

    /**
     * The users who get an event for this build. A waited-for build that succeeded stays silent: the upstream build
     * plays its own sound later. A waited-for build that did not succeed still plays, so a hidden failure is heard.
     * A user who is in both sets is told, because the path where nobody waits wins.
     */
    static Set<String> usersToTell(Set<String> notWaitedUsers, Set<String> waitedUsers, Sound sound) {
        Set<String> result = new LinkedHashSet<>(notWaitedUsers);
        if (sound == Sound.PROBLEM) {
            result.addAll(waitedUsers);
        }
        return result;
    }

    /**
     * Finds the users behind {@code cause} and puts them into one of the two sets.
     *
     * @param waited true if the build that finished is waited for by its direct upstream. It stays the same for
     *     every cause further up the chain.
     */
    void collectUsers(Cause cause, boolean waited, int depth, Set<String> notWaitedUsers, Set<String> waitedUsers) {
        if (cause instanceof Cause.UserIdCause) {
            String userId = ((Cause.UserIdCause) cause).getUserId();
            if (userId != null) {
                if (waited) {
                    waitedUsers.add(userId);
                } else {
                    notWaitedUsers.add(userId);
                }
            }
            return;
        }

        if (cause instanceof Cause.UpstreamCause && depth < MAX_DEPTH) {
            for (Cause nested : ((Cause.UpstreamCause) cause).getUpstreamCauses()) {
                collectUsers(nested, waited, depth + 1, notWaitedUsers, waitedUsers);
            }
        }
    }

    /**
     * True if the upstream build is still running and waiting for {@code run} to finish. The upstream run can be
     * null (for example the job was deleted); then nobody can be waiting.
     */
    private boolean isWaitedFor(Run<?, ?> run, Run<?, ?> upstreamRun) {
        if (upstreamRun == null || !upstreamRun.isBuilding()) {
            return false;
        }
        for (Action action : run.getAllActions()) {
            if (WAIT_MARKER_CLASS.equals(action.getClass().getName())) {
                return true;
            }
        }
        return false;
    }
}
