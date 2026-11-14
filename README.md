# Build Sound

Build Sound plays a sound in your browser, and can show a desktop notification, when a Jenkins build that you
started has finished. You keep a Jenkins tab open, switch to other work, and hear one sound for success and
another for a failed or unstable build. Only the person who started the build is told, and nothing is
configured by an administrator.

**Status:** pre-release. The server side is covered by automated tests. The browser script has no automated
tests, and its behaviour in Chrome, Firefox and Safari is still being verified by hand (see
[docs/manual-checklist.md](docs/manual-checklist.md)). Nothing here should be read as verified in a browser yet.

## Requirements

- Jenkins 2.568.3 or newer (the baseline in `pom.xml`).
- A single Jenkins controller. Events are kept in the memory of one controller process, so more than one
  controller replica behind a load balancer is not supported.
- Security enabled, because the plugin only works for logged-in users.
- For desktop notifications: a page served over HTTPS, or `localhost`. Browsers do not allow notifications on
  other plain HTTP pages. Sound does not have this requirement.

## Install

Install it like any other plugin. Until it is released, build it yourself (see "Build and run locally") and
upload `target/build-sound.hpi` under Manage Jenkins > Plugins > Advanced settings > Deploy Plugin. A plugin built
on your own machine records your local user name in its version string, which Jenkins shows to administrators, so
use a local build only on a Jenkins of your own.

Whether a Jenkins restart is needed after installing or removing the plugin has not been checked.

## Turn it on

Both features are off by default, and nothing is played or shown until you turn one on. Once you are logged in,
two small buttons appear at the bottom of Jenkins pages, in the footer row next to "REST API" and the version
number (in a narrow window, where Jenkins hides its footer, as a row at the end of the page):

- `Build sound: off` switches the sound on or off.
- `Desktop notifications: off` switches notifications on or off. The browser asks for permission only after you
  click it. The button is not shown when the browser or the page does not support notifications.

Each choice is stored per Jenkins user, per browser (in the browser's local storage). It is not stored on the
Jenkins server, so another browser, or another user on the same browser, starts with both switched off. While
both are off, the page does not poll the server, start a background worker or load the sound files.

## Which builds make a sound

A build makes a sound (or notification) for you when:

- You started it yourself: from the Jenkins page, through the API with your API token, or as a Pipeline Replay
  (the person who replays is the one who is told).
- It is a downstream build started by one of your builds:
  - If the build that directly triggered it is waiting for it, a successful result is silent (the upstream
    build's own sound follows). A failure or unstable result still plays the problem sound.
  - If nothing is waiting for it, it plays like any other build.

A success plays the success sound. A failure or unstable result plays the problem sound. Builds started by a
timer, by SCM polling or by a webhook have no person behind them and never make a sound. Aborted builds are
silent.

If several builds finish between two polls, there is at most one sound of each kind, and one summary
notification when there is more than one event. Only one open tab per browser plays or shows the result.

## What is not supported

- Sound or notifications while the browser tab is closed. There is no true push, and no mobile support.
- More than one controller replica.
- Several browsers for the same user: each browser that is logged in and switched on plays the sound.
- Choosing or uploading your own sounds.

## Privacy and security

- Events are kept in memory only, at most the last 20 per user, for 5 minutes. Nothing is written to disk, so
  events are lost when Jenkins restarts.
- The plugin makes no outbound network calls.
- The endpoint (`build-sound/latest` and `build-sound/events`) is read-only. It needs a logged-in user, returns
  only that user's own events, and leaves out events for jobs that user may not read.
- With desktop notifications on, the job name and build number are shown on screen and may be kept in the
  operating system's notification history.

## Known limits

- The "is the upstream waiting" check looks for a marker class that is private to the Pipeline build step plugin
  (`pipeline-build-step`). It is matched by class name. If that plugin renames the class, waited-for downstream
  builds will also play a sound (an extra sound, never a missing one).
- Not tested: Parameterized Trigger "block until finished".
- The Pipeline `build` step with `waitForStart: true` and `wait: false` left no marker on the downstream build when
  it was tried, so such a build is treated as not waited for and plays like any other build.
- A browser may refuse to play sound on a page you have not clicked or typed in. When that happens the tab steps
  aside so that a tab you have used can play instead; if desktop notifications are on, it keeps showing them.
  Which browsers do this has not been checked yet.
- A tab that has opted in keeps polling in the background, which may keep the Jenkins web session from timing out
  on idle. This is unverified.

## Upgrade and uninstall

The script, worker and sound URLs carry a short code derived from the plugin version, so after an upgrade the browser should fetch the new
files instead of old cached ones (not yet verified in browsers). Events in memory are lost when Jenkins restarts.
Saved on/off choices live only in the browser and are not removed by uninstalling the plugin. They are harmless
and can be cleared with the browser's site data. Whether a restart is needed to install, upgrade or remove the
plugin has not been checked.

## Sounds

Both sounds are original, generated by [`tools/make_sounds.py`](tools/make_sounds.py) from sine waves with the
Python standard library. Details and a way to verify them are in [THIRD_PARTY_SOUNDS.md](THIRD_PARTY_SOUNDS.md).

## Build and run locally

You need a JDK (the project is built and tested with Java 21; check the parent POM and Jenkins documentation for
the supported range) and Maven.

```
mvn verify      # compile, run the tests and the static checks
mvn hpi:run     # start a local Jenkins with the plugin; add -Dport=8090 to choose another port
```

The manual browser checks are in [docs/manual-checklist.md](docs/manual-checklist.md).

## Licence

MIT. See [LICENSE](LICENSE).
