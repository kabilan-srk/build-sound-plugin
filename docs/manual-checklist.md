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

Log in as `alice`. Keep the developer Console open. (These steps were written when the script only logged events
to the console. From Task 8 on it plays sounds instead, so in steps 4, 5 and 9 read "logs the event" as "makes the
sound", and use the Network tab to see the `events` replies.)

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

## Task 8: sounds, opt-in and tab handling

Use a fresh browser profile or clear site data for `localhost` first, so no old setting is stored. Turn the
computer volume to a normal level.

1. Log in as `alice` and look at the bottom right corner of any page.
   Expected: a small button `Build sound: off` with a dashed border. It does not cover or move any Jenkins content.
   (On a job's "Configure" page it floats over the bottom corner; it must not push the Save button around.)
2. Press Tab until the button has focus, then press Enter.
   Expected: the button can be reached with the keyboard, shows a focus outline, and switches to `Build sound: on`
   with a solid green border. Press Enter again: back to `off`. Leave it `on`.
3. Reload the page and look at the button.
   Expected: it still says `on` (the choice is remembered in the browser).
4. Opt-in is required. Set the button to `off`, click somewhere on the page, start `quick-ok` and wait.
   Expected: no sound at all. Then set it to `on` again.
5. Right sound for the result. With the button `on`, click once anywhere on the page (this lets the browser
   play audio), start `quick-ok`.
   Expected: about 10 seconds after it ends, the soft two-note rising chime. Then start `quick-fail`.
   Expected: the lower falling bell, clearly a different sound.
6. A tab nobody has clicked in cannot play. Reload the page, do **not** click or press any key in it, start a build
   from another window.
   Expected: no sound from the untouched tab. After you click once in the tab, the next build plays the sound.
7. Opting out silences it in every tab. Open two `alice` tabs, click in both, set the button to `off` in one.
   Expected: the other tab's button changes to `off` within a moment and the next build is silent.
8. Another user on the same browser has not opted in. Log out, log in as `bob` in the same browser, click once in
   the page and look at the button.
   Expected: it says `off`. Start a build as `bob` (give `bob` the same jobs, or create one): no sound until
   `bob` turns it on. Log back in as `alice`: her button still says `on`.
9. One tab plays. Open the same `alice` Jenkins page in **three** tabs, click once in each, with the button `on`.
   Start `quick-ok` and wait for the end.
   Expected: exactly one chime, not two or three. Close the tab that played (wait about 20 seconds for its claim to
   run out), start `quick-ok` again.
   Expected: one of the remaining tabs plays it, once.
10. Sound with the tab in the background. Click once in the `alice` tab, switch to another tab, start `quick-ok`
    from another window, stay on the other tab until it finishes.
    Expected: you hear the chime within about 10 seconds of the build ending. Repeat with the tab hidden for more
    than 8 minutes (see Task 7, step 12). The sound must still arrive within about 10 seconds.
11. A restart does not replay. With the button `on`, restart Jenkins (see Task 7, step 8).
    Expected: no sound when the tab reconnects.
12. Blocked storage does not break the page. In a private window with "block all cookies" (or Safari with
    "Block all cookies") log in, open the Console.
    Expected: no red errors from `build-sound.js`; the button shows `off` and may not remember changes.
13. The packaged plugin does not contain the generator. After `mvn package`, run
    `unzip -l target/build-sound.hpi | grep -E "make_sounds|sounds/"`.
    Expected: only `sounds/success.wav` and `sounds/problem.wav` are listed.
14. Repeat steps 2, 5, 6 and 9 in Chrome, Firefox and Safari. Safari is the one most likely to stay silent until the
    first click, and to show the button wrongly if something in the script is not supported: write down any
    difference.

## Task 8b: desktop notifications

Desktop notifications need a secure page: `http://localhost:8090/jenkins/` counts, a plain `http://` address on
another host does not. Also make sure the operating system lets the browser show notifications (macOS: System
Settings > Notifications > your browser, and Focus/Do Not Disturb off). Clear site data for `localhost` first
so no earlier permission is remembered. Log in as `alice`.

1. Look at the bottom right corner.
   Expected: next to `Build sound: ...` there is a second button `Desktop notifications: off`. No permission
   prompt has appeared yet, just from loading pages (reload a few times to be sure).
2. Click `Desktop notifications: off` and choose **Allow** in the browser prompt.
   Expected: the prompt appears only now, after your click. The button becomes `Desktop notifications: on`
   with a solid green border. Reload: it still says `on`.
3. Show a notification with sound off. Set `Build sound` to `off`, click once in the page, start `quick-ok`.
   Expected: no sound, but a system notification titled `Build succeeded` with the text `quick-ok #<number>`.
   Start `quick-fail`. Expected: a notification titled `Build failed or unstable` with `quick-fail #<number>`.
4. Notification with the tab in the background and in another app. Click once in the `alice` tab, switch to
   another tab (or app), start `quick-ok` from another window.
   Expected: the notification appears within about 10 seconds of the build ending, even though the tab is hidden.
   Also check it with `Build sound` on: you get the notification and the chime together.
5. Clicking the notification. Click it.
   Expected: the browser window comes to the front and a new tab opens on that build's page,
   `.../job/quick-ok/<number>/`. The tab you had open is unchanged. Repeat for a job inside a folder (create a folder
   `team` with a job `my app`, run it): the address is `.../job/team/job/my%20app/<number>/` and the page loads.
6. Two tabs show one notification. Open the `alice` page in three tabs, click once in each, start `quick-ok`.
   Expected: exactly one notification, not three.
7. Turning it off. Click the button so it says `off`, start a build.
   Expected: no notification. Any other open `alice` tab also shows `off` within a moment.
8. Denied permission. Reset the site permission for `localhost` (Chrome: padlock icon > Site settings > Notifications
   > Ask; Firefox and Safari: the site settings), reload, click the notification button and choose **Block**.
   Expected: the button stays off and then reads `Desktop notifications: blocked`; builds still play the sound if
   that is on; nothing is shown and the Console has no red errors. Allow it again in the site settings, reload,
   click the button: it turns `on`.
9. Permission taken away later. With the button `on`, block notifications in the site settings and reload.
   Expected: the button shows `blocked`; no error; the next build still plays the sound.
10. A different user on the same browser has not opted in. Log out, log in as `bob`, look at the buttons.
    Expected: `Desktop notifications: off` for `bob` while `alice` stays `on` when she logs back in.
11. Not a secure page. Start Jenkins with `-Dport=8090 -Dhost=0.0.0.0` and open
    `http://<your computer's LAN address>:8090/jenkins/` (for example `http://192.168.1.20:8090/jenkins/`).
    Log in as `alice`, open the Console.
    Expected: only `Build sound: ...` is shown, there is no notification button, no permission prompt, no red errors
    in the Console, and a build still plays the sound once you opted in.
12. The job name is plain text. If you made the `x<b>y` job in Task 7 step 10, run it.
    Expected: the notification text shows `x<b>y` exactly as written (no bold text).
13. Safari. Repeat steps 2, 3, 5 and 6 in Safari, which asks for permission only after a click and may also ask in
    macOS system settings. Also repeat steps 2, 3 and 6 in Chrome and Firefox.
