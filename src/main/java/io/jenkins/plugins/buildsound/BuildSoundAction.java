package io.jenkins.plugins.buildsound;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.RootAction;
import hudson.model.User;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.GET;
import org.springframework.security.access.AccessDeniedException;

/**
 * The read-only HTTP endpoint the browser script polls: {@code <jenkins>/build-sound/latest} and
 * {@code <jenkins>/build-sound/events?after=N}. It only ever returns the logged-in caller's own events.
 */
@Extension
public class BuildSoundAction implements RootAction {

    // Made once when the plugin loads. If Jenkins restarts it changes, which tells the browser that the
    // event ids start again from 1, so its saved cursor is no longer valid.
    private final String instanceId = UUID.randomUUID().toString();

    // No icon and no display name: this is not a page in the menu, only an endpoint.
    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "build-sound";
    }

    /** The newest event id of the caller. The page calls this once on load, so it never replays old events. */
    @GET
    public void doLatest(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        String userId = callerId();
        if (userId == null) {
            sendError(response, 403);
            return;
        }
        // The newest of the caller's events that the caller may read; 0 if there is none.
        long latest = 0;
        for (BuildEvent event : EventStore.instance().eventsSince(userId, 0)) {
            if (callerCanRead(event.getJobFullName())) {
                latest = event.getId();
            }
        }
        send(response, startBody(userId, latest));
    }

    /** The caller's events with an id greater than {@code after}. */
    @GET
    public void doEvents(StaplerRequest2 request, StaplerResponse2 response) throws IOException {
        String userId = callerId();
        if (userId == null) {
            sendError(response, 403);
            return;
        }

        long after;
        try {
            after = Long.parseLong(request.getParameter("after"));
        } catch (NumberFormatException e) {
            // Also happens when the parameter is missing (null). Nothing from the request is echoed back.
            sendError(response, 400);
            return;
        }

        JSONArray events = new JSONArray();
        // One read gives us both the events and, from them, the new cursor. Nothing else is read from the store,
        // so a build that finishes during this request cannot be skipped: it shows up in the next poll.
        List<BuildEvent> found = EventStore.instance().eventsSince(userId, after);
        // The cursor only moves past events the caller may read. A hidden build must not move it, or the caller
        // could tell that some build finished. (Event ids are shared by all users, so a gap in the ids only
        // shows that other events exist, never what they are.)
        long latest = after;
        for (BuildEvent event : found) {
            if (!callerCanRead(event.getJobFullName())) {
                continue; // job deleted, or the caller may not see it: leave the event out
            }
            JSONObject json = new JSONObject();
            json.put("id", event.getId());
            json.put("job", event.getJobFullName());
            json.put("number", event.getBuildNumber());
            json.put("sound", event.getSound().name().toLowerCase(Locale.ROOT));
            events.add(json);
            latest = event.getId();
        }
        JSONObject body = startBody(userId, latest);
        body.put("events", events);
        send(response, body);
    }

    /**
     * True if the job still exists and the caller may read it. This runs inside the caller's own request, so
     * Jenkins checks the permission for the caller and not for the system. Do not wrap it in a SYSTEM context.
     */
    private static boolean callerCanRead(String jobFullName) {
        try {
            Item item = Jenkins.get().getItemByFullName(jobFullName);
            return item != null && item.hasPermission(Item.READ);
        } catch (AccessDeniedException e) {
            // Jenkins throws this when the caller may see that a job exists (DISCOVER) but not read it.
            return false;
        }
    }

    /**
     * The part both answers share: which Jenkins run this is, who the answer is for (always the caller's own id,
     * never anything from the request) and the cursor the page should use next.
     */
    private JSONObject startBody(String userId, long latest) {
        JSONObject body = new JSONObject();
        body.put("instance", instanceId);
        body.put("user", userId);
        body.put("latest", latest);
        return body;
    }

    /**
     * The id of the logged-in caller in the same form the listener stores ({@code UserIdCause.getUserId()} is
     * {@code User.getId()}), or null for an anonymous caller.
     */
    private static String callerId() {
        User user = User.current();
        return user == null ? null : user.getId();
    }

    private static void send(StaplerResponse2 response, JSONObject body) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        // Events are per user and change every few seconds: nobody in between may keep a copy.
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(body.toString());
    }

    private static void sendError(StaplerResponse2 response, int status) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.sendError(status);
    }
}
