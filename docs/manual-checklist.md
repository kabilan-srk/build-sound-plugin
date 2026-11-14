# Manual browser checks

The browser script has no automated tests, so these checks are done by hand in a real browser. Do the steps in order
and compare what you see with "Expected". Write down every step that does not match.

Repeat the checks in **Chrome, Firefox and Safari** (current versions). Safari is the strictest about sound and
notifications, so do not skip it. Browsers change their rules for background tabs and audio between versions, so
repeat the "background" and "no click" checks after big browser updates.

## 0. Set up a local Jenkins (once)

1. In the project folder start Jenkins with the plugin loaded. You need a JDK that Jenkins supports (here Java 21);
   point `JAVA_HOME` at it. Example for a Mac with Homebrew:
   `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn hpi:run -Dport=8090`
   (any free port except 8080 is fine).
2. Wait for the line `Jenkins is fully up and running`, then open <http://localhost:8090/jenkins/>.
   Expected: the Jenkins dashboard. `localhost` counts as a secure context, which desktop notifications need.
3. Turn security on, because the plugin only works for logged-in users:
   Manage Jenkins > Security > Security Realm: "Jenkins' own user database", tick "Allow users to sign up";
   Authorization: "Logged-in users can do anything"; Save.
4. Sign up the user `alice`, then log out and sign up `bob`.
5. As `alice` create three Freestyle jobs, each with one "Execute shell" build step:
   - `quick-ok` with the command `sleep 5`
   - `quick-ok-2` with the command `sleep 5`
   - `quick-fail` with the command `sleep 5; exit 1`
6. Open the browser's developer tools (Console and Network tabs). In Chrome, tick "Show timestamps" in the Console
   settings.

Before each browser's run, clear the site data for `localhost`, so no earlier choice is remembered. Use a normal
computer volume.

## 1. Nothing happens before you opt in

1. With clean site data, log in as `alice`, open the Network tab and reload any page.
   Expected: the page loads `plugin/build-sound/build-sound.js?v=...` (status 200) and nothing else from the plugin:
   no request to `build-sound/latest` or `build-sound/events`, no `build-sound-worker.js`, no `.wav` file. Wait a
   minute: still no plugin requests.
2. Scroll to the bottom of the page and look at the footer row.
   Expected: a small button `Build sound: off` with a dashed border and (on a secure page) a second button
   `Desktop notifications: off`, next to "REST API" and the Jenkins version. They do not cover any Jenkins
   content; check a build page and a job's "Configure" page too. Make the window narrow until Jenkins hides its
   footer: the buttons then form a row at the very end of the page. No permission prompt has appeared.
3. Press Tab until `Build sound` has focus, then press Enter.
   Expected: the button can be reached with the keyboard, shows a focus outline, and switches to `Build sound: on`
   with a solid green border. In the Network tab a request `build-sound/latest` appears (answered 200 with JSON such
   as `{"instance":"...","user":"alice","latest":0}`), `build-sound-worker.js?v=...` loads (200), and the two
   `.wav` files load. Then a request `build-sound/events?after=0` follows every 5 seconds.
4. Press Enter again.
   Expected: the button says `off` again and the requests stop completely (no more `events` calls).
5. Switch it `on` again and reload the page.
   Expected: it still says `on` (the choice is remembered in this browser) and the first plugin request after the
   reload is `build-sound/latest`.
6. The login page. Open <http://localhost:8090/jenkins/login> in a private window (logged out), and also while
   logged in as `alice`.
   Expected: no `build-sound.js` request in either case.

## 2. Events and polling

Log in as `alice`, turn `Build sound` on, keep the Network tab open.

1. Start `quick-ok` (the play button) and wait for it to finish.
   Expected: within about 10 seconds of the build ending, an `events` reply contains one event
   (`"sound":"success"`, `"job":"quick-ok"`, plus a `"jobDisplay"` field) and you hear the success chime.
2. Start `quick-fail`.
   Expected: the `events` reply has `"sound":"problem"` and you hear the lower falling bell, clearly different.
3. Reload the page.
   Expected: no old event is played. The first request after the reload is `build-sound/latest`.
4. Start `quick-ok` again, then abort it with the red X while it runs.
   Expected: no sound (an aborted build is silent).
5. Jenkins restart. Stop Jenkins (Ctrl+C in the Maven window), start it again (step 1 of "Set up"), and leave the
   `alice` tab open.
   Expected: while Jenkins is down nothing is shown in the page; after it is back (log in again if asked) no old
   event is played, and a newly started build plays once.
6. Another user does not hear it. In a second browser (or a private window) log in as `bob`, turn `Build sound` on,
   and start a build as `alice`.
   Expected: `alice` hears it, `bob` does not, and bob's `events` replies contain `"events":[]`.
7. Job names are plain text. Run
   `grep -n "innerHTML\|insertAdjacentHTML\|document.write" src/main/webapp/*.js`.
   Expected: no output. Then (best effort) stop Jenkins, copy `work/jobs/quick-ok` to `work/jobs/x<b>y`, start
   Jenkins and run that job as `alice` with notifications on. Expected: the notification shows `x<b>y` exactly as
   written, no bold text. (Jenkins refuses `<` when a job is created in the UI, which is why the folder is copied
   by hand. If Jenkins does not load the copied job, note it and skip this part.)
8. Worker fallback. In Chrome open the Network tab, right-click the `build-sound-worker.js` request, choose
   "Block request URL", and reload.
   Expected: one Console line starting `[build-sound] using a page timer instead of a Web Worker`, printed once
   (not every 5 seconds), and `events` requests still go out. Remove the block afterwards.
9. The Worker is allowed by the page's security settings. With the plugin switched on, reload and read the Console.
   Expected: no `Content-Security-Policy` message, and `build-sound-worker.js` shows status 200. If your Jenkins is
   behind a proxy that adds a `Content-Security-Policy` header, repeat this step with that proxy in place: the
   header must allow workers from the same origin (`worker-src 'self'`), or the script falls back to the page timer.
10. Long hidden-tab check. With the `alice` tab loaded, switch to another tab (or another app) for **more than
    8 minutes**. Shortly before the end, start `quick-ok` from a second window that stays visible, then leave the
    `alice` tab hidden until the build has finished. Switch back.
    Expected: the sound played within about 10 seconds of the build's finish time (see the build page), and the
    `events` requests in the Network tab show steady 5-second spacing (not one per minute) during the hidden time.

## 3. Sound and tabs

1. The browser does not need a click first. Log in, turn `Build sound` on, then reload the page, so this page load
   has had **no click and no key press**. Start `quick-ok` from another window, and switch to another tab or app
   without clicking anything on the page you land on.
   Expected, and record the result for each browser: the chime plays, or the browser blocks it and nothing plays
   (Safari is the most likely to block). If it is blocked, click once anywhere in the Jenkins tab and start the build
   again: it must play now.
2. A blocked tab hands over to another tab. Open two `alice` tabs. In tab 1 click once (this lets that tab play).
   Open tab 2 and do **not** click in it. Start `quick-ok` from another window.
   Expected: exactly one chime, from whichever tab the browser allows to play. If the browser blocked the tab that
   tried first, the other tab takes over for the next build.
3. Opting out silences every tab. Open two `alice` tabs, set the button to `off` in one.
   Expected: the other tab's button changes to `off` within a moment, its `events` requests stop, and the next
   build is silent. Turn it on in one tab: the other tab's button changes to `on` and polling starts again from "now"
   (no old event is played).
4. Another user on the same browser has not opted in. Log out, log in as `bob` in the same browser and look at the
   buttons.
   Expected: `off`, no plugin requests, no sound for `bob`'s builds until `bob` turns it on. Log back in as
   `alice`: her button still says `on`.
5. One tab plays. Open the same `alice` Jenkins page in **three** tabs, with the button `on`. Start `quick-ok`.
   Expected: exactly one chime, not two or three. Close the tab that played and start `quick-ok` again.
   Expected: one of the remaining tabs plays it, once. A closed tab gives its claim up at once, so the other tab
   takes over on its next 5-second check; there is no need to wait.
6. Several browser windows opened at the same time may rarely play twice (two tabs can briefly both believe they
   hold the claim). Note it if you see it.
7. Sound with the tab in the background. Switch to another tab, start `quick-ok` from another window, stay on the
   other tab until it finishes.
   Expected: you hear the chime within about 10 seconds of the build ending.
8. Blocked storage does not break the page. In a private window with "block all cookies" (or Safari with "Block all
   cookies") log in and open the Console.
   Expected: no red errors from `build-sound.js`; the button shows `off` and may not remember changes.
9. The packaged plugin does not contain the generator. After `mvn package`, run
   `unzip -l target/build-sound.hpi | grep -E "make_sounds|sounds/"`.
   Expected: only `sounds/success.wav` and `sounds/problem.wav` are listed.
10. Blocked sound still notifies. Turn both `Build sound` and `Desktop notifications` on, then reload, so the page has
    had **no click and no key press**. Keep this as the only `alice` tab. Start `quick-ok` three times in a row.
    Expected: a notification for each of the three builds, even if the browser blocked the sound (record whether it
    did). Then open a second `alice` tab, click once anywhere in it, and start `quick-ok` again.
    Expected: the chime comes from the second tab and there is exactly one notification (not two). The first tab
    stays quiet from then on.

## 4. Desktop notifications

Desktop notifications need a secure page: `http://localhost:8090/jenkins/` counts, a plain `http://` address on
another host does not. Make sure the operating system lets the browser show notifications (macOS: System Settings >
Notifications > your browser, and Focus/Do Not Disturb off). Clear site data for `localhost` first. Log in as `alice`.

1. Reload a few times.
   Expected: next to `Build sound: ...` there is a button `Desktop notifications: off`, and no permission prompt has
   appeared.
2. Click `Desktop notifications: off` and choose **Allow** in the browser prompt.
   Expected: the prompt appears only now, after your click. The button becomes `Desktop notifications: on` with a
   solid green border, and a `build-sound/latest` request appears. Reload: it still says `on`.
3. Notification with sound off and no click. Set `Build sound` to `off`, reload (so the page has had no click), start
   `quick-ok`.
   Expected: no sound, but a system notification titled `Build succeeded` with the text `quick-ok #<number>`. Start
   `quick-fail`: a notification titled `Build failed or unstable` with `quick-fail #<number>`.
4. Notification with the tab in the background and in another app. Switch to another tab (or app), start `quick-ok`
   from another window.
   Expected: the notification appears within about 10 seconds of the build ending, although the tab is hidden. Also
   check it with `Build sound` on: you get the notification and the chime together.
5. Clicking the notification.
   Expected: the browser window comes to the front and a new tab opens on that build's page,
   `.../job/quick-ok/<number>/`. The tab you had open is unchanged. Repeat for a job inside a folder (create a folder
   `team` with a job `my app` and run it): the address is `.../job/team/job/my%20app/<number>/` and the page loads.
6. Display names. Give the folder `team` the display name `Team A` and the job `my app` the display name `The App`,
   run it.
   Expected: the notification text reads `Team A » The App #<number>`, and clicking it still opens the right build page.
7. Two tabs show one notification. Open the `alice` page in three tabs, start `quick-ok`.
   Expected: exactly one notification, not three.
8. Turning it off. Click the button so it says `off`, start a build.
   Expected: no notification, and the plugin requests stop if sound is off too. Any other open `alice` tab shows
   `off` within a moment.
9. Denied permission. Reset the site permission for `localhost` (Chrome: padlock icon > Site settings > Notifications
   > Ask; Firefox and Safari: the site settings), reload, click the notification button and choose **Block**.
   Expected: the button stays off and then reads `Desktop notifications: blocked`; builds still play the sound if that
   is on; nothing is shown and the Console has no red errors. Allow it again in the site settings, reload, click the
   button: it turns `on`.
10. Permission taken away later. With the button `on`, block notifications in the site settings and reload.
    Expected: the button shows `blocked`; no error; the next build still plays the sound if that is on.
11. A different user on the same browser has not opted in. Log out, log in as `bob`.
    Expected: `Desktop notifications: off` for `bob` while `alice` stays `on` when she logs back in.
12. Not a secure page. Start Jenkins with `-Dport=8090 -Dhost=0.0.0.0` and open
    `http://<your computer's LAN address>:8090/jenkins/` (for example `http://192.168.1.20:8090/jenkins/`).
    Log in as `alice`, open the Console.
    Expected: only `Build sound: ...` is shown, there is no notification button, no permission prompt, no red errors
    in the Console, and a build still plays the sound once you opted in.
13. Safari asks for permission only after a click and may also ask in macOS system settings: repeat steps 2, 3, 5 and
    7 in Safari, then steps 2, 3 and 7 in Chrome and Firefox.

## 5. Several builds finishing together

Turn both `Build sound` and `Desktop notifications` on as `alice`, and make sure at least two build executors are free
(Manage Jenkins > Nodes > Built-In Node, 3 executors or more).

1. Start `quick-ok`, `quick-ok-2` and `quick-fail` within a second or two of each other.
   Expected: after they finish (all three end within a few seconds of each other) you get **one** notification titled
   `3 builds finished` listing the builds as plain text lines (`quick-ok #..` and so on), not three notifications.
   If they happen to land in different polls, you get separate ones: repeat with a longer-running `quick-ok-2`.
   The sounds do not overlap: the low problem bell plays first, then the success chime once (not twice).
2. Click the summary notification.
   Expected: the window comes to the front and a new tab opens on the Jenkins start page.
3. With only `Build sound` on, repeat step 1. Expected: the same ordered sounds, no notification.

## 6. When things go wrong

1. Back-off after the session ends. Log in as `alice` with sound on, keep the Network tab open, then log out from a
   **different** window (the first tab stays open and keeps its old page).
   Expected: the first tab's `events` requests start failing with status 403 and the gaps between them grow:
   about 10 seconds, then 20, 40, 80 and so on up to 5 minutes, instead of a request every 5 seconds. Log in again
   in another window as `alice` and wait: the next request succeeds and the 5-second pace returns. A network
   error or a 5xx reply (stop Jenkins briefly) does not slow the pace.
2. A second user logs in while the first user's tab is open. Keep an `alice` tab open with sound on. In another
   tab of the same browser log out and log in as `bob`.
   Expected: the old `alice` tab's next reply says `"user":"bob"`; after that it sends no more plugin requests and
   plays nothing (check that `bob`'s builds do not play from the old tab). Reload that tab: it now belongs to `bob`.
3. Does a background tab keep the Jenkins session alive? This is not known yet: record what you see. With the
   plugin switched on in a tab that you never touch, leave Jenkins untouched for longer than your session timeout
   (the default is 30 minutes of no requests), then reload. Do the same once with the plugin switched off in a tab.
   Write down whether you are still logged in in each case. (The polling requests may count as activity.)
4. Jenkins without a context path. Run Jenkins at the root of a port: copy `target/build-sound.hpi` into
   `<jenkins home>/plugins/` and start a Jenkins war of the same version with
   `java -jar jenkins.war --httpPort=8091` (with `JENKINS_HOME` set to that folder), then open
   <http://localhost:8091/>.
   Expected: sound and notifications work as before; the Network tab shows requests like `/build-sound/latest` and
   `/plugin/build-sound/build-sound.js` (no `/jenkins` in the paths), and a notification click opens `/job/.../`.
