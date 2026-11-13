# Manual browser checks

The browser script has no automated tests in version 1, so these checks are done by hand. Do each step in order and
compare what you see with "Expected". Write down any step that does not match.

Browsers to repeat the checks in: **Chrome, Firefox and Safari** (current versions). Safari is the strictest about
sound and notifications, so do not skip it.

## 0. Set up a local Jenkins (once)

1. In the project folder run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn hpi:run -Dport=8090`
   (any free port except 8080 is fine; `port` is the property of the hpi plugin).
2. Wait for the line `Jenkins is fully up and running`, then open <http://localhost:8090/jenkins/>.
   Expected: the Jenkins dashboard. `localhost` counts as a secure context, which desktop notifications need.
3. Turn security on, because the plugin only works for logged-in users:
   Manage Jenkins > Security > Security Realm: "Jenkins' own user database", tick "Allow users to sign up";
   Authorization: "Logged-in users can do anything"; Save.
4. Sign up the user `alice`, then log out and sign up `bob`.
5. As `alice` create two Freestyle jobs, each with one "Execute shell" build step:
   - `quick-ok` with the command `sleep 5`
   - `quick-fail` with the command `sleep 5; exit 1`
6. Open your browser's developer tools (Chrome and Safari: Console tab; Firefox: Console tab). In Chrome open the
   Console settings and tick "Show timestamps".

## Task 7: page script and polling

Log in as `alice`. Keep the developer Console open. The script only writes lines starting with `[build-sound]`.

1. Open any Jenkins page as `alice` and look at the Network tab (reload the page).
   Expected: a request for `plugin/build-sound/build-sound.js?v=...` and one for `build-sound-worker.js?v=...`,
   both status 200, and a `build-sound/latest` request answered 200 with JSON like `{"instance":"...","latest":0}`.
   Then every 5 seconds a request `build-sound/events?after=0`.
2. Open the login page while logged out (private window, <http://localhost:8090/jenkins/login>).
   Expected: no `build-sound.js` request, no `build-sound/` requests.
3. As `alice` open <http://localhost:8090/jenkins/login> (while still logged in).
   Expected: no `build-sound.js` request.
4. As `alice` start `quick-ok` (the play button) and wait for it to finish.
   Expected: within about 10 seconds of the build ending, one console line `[build-sound] event success quick-ok 1`.
5. Start `quick-fail`.
   Expected: one console line `[build-sound] event problem quick-fail 1`.
6. Reload the page.
   Expected: no old events are logged. The first request after the reload is `build-sound/latest`.
7. Start `quick-ok` again, then abort it with the red X while it runs.
   Expected: nothing is logged (an aborted build is silent).
8. Stop Jenkins (Ctrl+C in the Maven window), start it again (step 1 of "Set up"), and leave the alice tab open.
   Expected: while Jenkins is down nothing is shown in the page; after it is back (log in again if asked) the
   tab does not log any old event, and a newly started build is logged once.
9. In a second browser (or a private window) log in as `bob` with the Console open. Start a build as `alice`.
   Expected: `alice` logs the event, `bob` logs nothing, and bob's `events` replies contain `"events":[]`.
10. Job name is never treated as HTML. Run `grep -n "innerHTML\|insertAdjacentHTML\|document.write" src/main/webapp/*.js`.
    Expected: no output. Then (best effort) stop Jenkins, copy `work/jobs/quick-ok` to `work/jobs/x<b>y`, start
    Jenkins and run that job as `alice`. Expected: the console line shows the name `x<b>y` as plain text
    (Jenkins refuses `<` when creating a job in the UI, which is why the folder is copied by hand; if Jenkins does
    not load the copied job, note it and skip this part).
11. Worker fallback. In Chrome open Network, right-click the `build-sound-worker.js` request, choose
    "Block request URL", reload.
    Expected: one console line starting `[build-sound] using a page timer instead of a Web Worker`, printed once
    (not every 5 seconds), and `events` requests still go out every 5 seconds. Remove the block afterwards.
12. Long hidden-tab check. With the `alice` tab loaded, switch to another tab (or another app) for **more than
    8 minutes**. Shortly before the end, start `quick-ok` from a second window of the browser that stays visible,
    or from the Jenkins page on another device, then leave the `alice` tab hidden until the build has finished.
    Switch back to the `alice` tab.
    Expected: the console line for the event has a timestamp within about 10 seconds of the build's finish time
    (see the build page), and the `events` requests in the Network tab show steady 5-second spacing (not one per
    minute) during the hidden time. Do this in Chrome, Firefox and Safari. Browsers change this behaviour
    between versions, so repeat it after big browser updates.
