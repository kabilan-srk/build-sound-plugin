// Build Sound: asks Jenkins every 5 seconds whether a build started by this user has finished, and plays
// a sound and/or shows a desktop notification when one has.
// Loaded on every Jenkins page for logged-in users (see BuildSoundDecorator).
// Nothing runs (no requests, no timer, no audio) until the user turns sound or notifications on.
(function () {
    "use strict";

    // Jenkins pages that are shown inside another page would poll twice. Only the top page works.
    if (window.top !== window.self) {
        return;
    }

    var POLL_MS = 5000;
    var MAX_WAIT_MS = 5 * 60 * 1000; // the longest pause between requests after Jenkins refused us
    var CLAIM_MS = 15000; // a tab's claim to play runs out if it is not renewed for this long (a closed tab)

    // The values Jenkins gave us in the data- attributes of our own <script> tag.
    var script = document.currentScript;
    if (!script) {
        return;
    }
    var rootUrl = script.getAttribute("data-root-url") || "";
    var resourceUrl = script.getAttribute("data-resource-url") || "";
    var version = script.getAttribute("data-version") || "";
    var userId = script.getAttribute("data-user-id") || "";

    // What we remember between polls.
    var running = false; // true while we poll: only when sound or notifications are on
    var runId = 0; // changes on every start and stop, so a reply that arrives late is recognised and dropped
    var wrongUser = false; // true once Jenkins answered for somebody else: this page never polls again
    var instance = null; // which Jenkins run answered last (changes when Jenkins restarts)
    var cursor = null; // the "latest" value of the last good reply; null means "ask where now is first"
    var busy = false; // true while a request is in flight, so slow replies never pile up
    var notBefore = 0; // after a refusal (401, 403, 404) no request is sent before this time (ms)
    var waitMs = POLL_MS; // how long we wait after the next refusal; doubles each time

    // Sound state, declared here because the functions below use it.
    var sounds = null; // the two Audio objects, created only when sound is first wanted
    var primed = false; // true once we started both sounds silently inside a click or key press
    var soundBlockedHere = false; // true when the browser refused to play in this tab, until the next click or key

    // ---- browser storage ---------------------------------------------------------------------------------

    // localStorage can be blocked (private mode, strict settings) or full, and then even touching it throws.
    // These three helpers swallow that, so a broken storage means "no setting" and never a script error.
    function storageGet(key) {
        try {
            return window.localStorage.getItem(key);
        } catch (e) {
            return null;
        }
    }

    function storageSet(key, value) {
        try {
            window.localStorage.setItem(key, value);
        } catch (e) {}
    }

    function storageRemove(key) {
        try {
            window.localStorage.removeItem(key);
        } catch (e) {}
    }

    // The user id is in every key, so on a shared browser each Jenkins user has their own choices.
    var SOUND_KEY = "build-sound.sound." + userId;
    var NOTIFY_KEY = "build-sound.notify." + userId;
    var CLAIM_KEY = "build-sound.claim." + userId;

    // The choice is read every time it is needed (not kept in a variable), so switching it in one tab
    // takes effect in all tabs at once.
    function soundEnabled() {
        return storageGet(SOUND_KEY) === "on";
    }

    // ---- talking to Jenkins ------------------------------------------------------------------------------

    // After a refusal, wait twice as long as the time before (up to 5 minutes). The tick still comes every
    // 5 seconds, so "waiting" means the tick does nothing until notBefore has passed.
    function slowDown() {
        waitMs = Math.min(waitMs * 2, MAX_WAIT_MS);
        notBefore = Date.now() + waitMs;
    }

    // Asks one endpoint and calls onReply(data) with the parsed JSON, or not at all when anything is wrong.
    // Network errors, timeouts, 5xx and non-JSON replies are ignored on purpose: the next tick tries again at
    // the normal pace.
    function ask(path, onReply) {
        if (busy) {
            return;
        }
        busy = true;
        var thisRun = runId;
        var options = { credentials: "same-origin", cache: "no-store", headers: { Accept: "application/json" } };
        // Do not let one hung request block all later polls.
        var timer = null;
        if (typeof AbortController === "function") {
            var controller = new AbortController();
            options.signal = controller.signal;
            timer = setTimeout(function () {
                controller.abort();
            }, 15000);
        }
        fetch(rootUrl + "/build-sound/" + path, options)
            .then(function (response) {
                if (thisRun !== runId) {
                    return null; // we were stopped meanwhile
                }
                if (response.status === 401 || response.status === 403 || response.status === 404) {
                    slowDown(); // not logged in, not allowed, or the plugin is gone: stop hammering
                    return null;
                }
                if (response.status === 400) {
                    cursor = null; // Jenkins did not understand our cursor: ask where "now" is again
                    return null;
                }
                return response.ok ? response.text() : null;
            })
            .then(function (text) {
                var data = null;
                try {
                    data = text === null ? null : JSON.parse(text);
                } catch (e) {
                    data = null; // not JSON
                }
                var valid =
                    data &&
                    typeof data.instance === "string" &&
                    typeof data.user === "string" &&
                    typeof data.latest === "number";
                if (!valid || thisRun !== runId) {
                    return;
                }
                if (data.user !== userId) {
                    // The answer is for somebody else (the browser is now logged in as another user). Never use
                    // it, and stop for good: this page was rendered for the old user.
                    wrongUser = true;
                    stopWorking();
                    return;
                }
                waitMs = POLL_MS; // a good reply ends any slow-down
                notBefore = 0;
                onReply(data);
            })
            .catch(function () {})
            .then(function () {
                // Runs after success and after failure alike (like "finally"): free the slot for the next request.
                clearTimeout(timer);
                busy = false;
            });
    }

    function poll() {
        if (cursor === null) {
            // First contact (turned on, page load or after a failed start): only learn where "now" is, so an
            // old event is never replayed.
            ask("latest", function (data) {
                instance = data.instance;
                cursor = data.latest;
            });
            return;
        }
        ask("events?after=" + encodeURIComponent(cursor), function (data) {
            if (data.instance !== instance) {
                // Jenkins restarted: its event numbers start again, so our old cursor means nothing.
                // Forget it; the next tick asks where "now" is, and nothing old is played.
                instance = null;
                cursor = null;
                return;
            }
            if (Array.isArray(data.events) && data.events.length > 0) {
                handleEvents(data.events);
            }
            // Use "latest", not the highest event seen: it is the newest event we were allowed to see.
            cursor = data.latest;
        });
    }

    // Called every 5 seconds by the clock (see "the clock" below).
    function tick() {
        if (!running) {
            return;
        }
        if (!wantsAnything()) {
            stopWorking(); // for example the browser permission for notifications was taken away
            return;
        }
        renewClaim();
        if (Date.now() < notBefore) {
            return; // slowing down after a refusal
        }
        poll();
    }

    // ---- which tab plays ---------------------------------------------------------------------------------

    // Every Jenkins tab of the same browser runs this script, but only one should make a sound.
    // The tabs share localStorage, so the winner writes a "claim" there: its own tab id and the time.
    // The tab holding the claim renews it on every tick; if it stops (the tab was closed) the claim gets old
    // and another tab takes over.
    var tabId = Math.random().toString(36).slice(2);

    function readClaim() {
        try {
            var claim = JSON.parse(storageGet(CLAIM_KEY));
            if (claim && typeof claim.tab === "string" && typeof claim.time === "number") {
                return claim;
            }
        } catch (e) {}
        return null;
    }

    // Can this tab take part in the claim at all? A tab where the browser blocked sound stays out until
    // the user clicks or presses a key in it, so a tab the user did use can take over.
    function mayClaim() {
        if (!running || !wantsAnything()) {
            return false;
        }
        return !(soundEnabled() && soundBlockedHere);
    }

    // Takes the claim if it is free, or renews it if it is ours.
    function renewClaim() {
        if (!mayClaim()) {
            return;
        }
        var claim = readClaim();
        var free = claim === null || Date.now() - claim.time > CLAIM_MS;
        if (free || claim.tab === tabId) {
            storageSet(CLAIM_KEY, JSON.stringify({ tab: tabId, time: Date.now() }));
        }
    }

    // True if this tab holds the claim. Two tabs can write at the same moment and the later write wins, so
    // for a very short time both may believe they hold it; a rare double sound is possible.
    function holdsClaim() {
        var claim = readClaim();
        return claim !== null && claim.tab === tabId;
    }

    function releaseClaim() {
        if (holdsClaim()) {
            storageRemove(CLAIM_KEY);
        }
    }

    // When this tab closes, give the claim up at once instead of making the others wait for it to run out.
    window.addEventListener("pagehide", releaseClaim);

    // ---- sound -------------------------------------------------------------------------------------------

    // Browsers refuse to play audio on a page the user has not touched yet. So on the first click or key press
    // (after sound is on) we start both sounds silently once, which tells the browser audio is allowed.
    function ensureSounds() {
        if (sounds !== null) {
            return;
        }
        sounds = {};
        ["success", "problem"].forEach(function (name) {
            var audio = new Audio(resourceUrl + "/sounds/" + name + ".wav?v=" + encodeURIComponent(version));
            audio.preload = "auto";
            sounds[name] = audio;
        });
    }

    function primeAudio() {
        if (!soundEnabled()) {
            return;
        }
        ensureSounds();
        if (primed) {
            return;
        }
        primed = true;
        Object.keys(sounds).forEach(function (name) {
            var audio = sounds[name];
            audio.muted = true;
            var started = null;
            try {
                started = audio.play();
            } catch (e) {}
            var undoSilentStart = function () {
                audio.pause();
                audio.currentTime = 0;
                audio.muted = false;
            };
            if (started && started.then) {
                started.then(undoSilentStart, function () {
                    audio.muted = false;
                    primed = false; // it did not work: try again at the next click
                });
            } else {
                undoSilentStart();
            }
        });
    }

    // Every click or key press in this page lets the browser play audio again, so it ends a "blocked" state.
    function onUserGesture() {
        soundBlockedHere = false;
        primeAudio();
    }

    document.addEventListener("pointerdown", onUserGesture, true);
    document.addEventListener("keydown", onUserGesture, true);

    // The browser refused to play. Give the claim up so a tab the user did click in can take it, and do not
    // take it back until the user clicks or presses a key here.
    function onSoundBlocked() {
        soundBlockedHere = true;
        primed = false;
        releaseClaim();
    }

    function playSound(name) {
        ensureSounds();
        var audio = sounds[name];
        try {
            audio.currentTime = 0;
            var started = audio.play();
            if (started && started.catch) {
                started.catch(onSoundBlocked);
            }
        } catch (e) {
            onSoundBlocked();
        }
    }

    // ---- desktop notifications ---------------------------------------------------------------------------

    // Browsers only allow notifications on secure pages (https, or localhost). If the page is not secure or
    // the browser has no Notification support, the whole feature is switched off and the button is not shown.
    function notificationsSupported() {
        return window.isSecureContext === true && typeof window.Notification === "function";
    }

    function notificationPermission() {
        try {
            return window.Notification.permission;
        } catch (e) {
            return "denied";
        }
    }

    // On means two things: the user chose it, and the browser still allows it.
    function notificationsEnabled() {
        return notificationsSupported() && storageGet(NOTIFY_KEY) === "on" && notificationPermission() === "granted";
    }

    // The page of one build, for example job/folder/job/app/12/ for the job "folder/app" build 12.
    // Every part of the name is URL-encoded, so a name with spaces or odd characters cannot break the address.
    function buildUrl(jobFullName, number) {
        var parts = String(jobFullName).split("/").map(function (part) {
            return "job/" + encodeURIComponent(part) + "/";
        });
        return rootUrl + "/" + parts.join("") + encodeURIComponent(String(number)) + "/";
    }

    function showNotification(event) {
        try {
            var title = event.sound === "success" ? "Build succeeded" : "Build failed or unstable";
            // The job name goes in as plain text: a notification never treats it as HTML.
            var notification = new Notification(title, {
                body: event.job + " #" + event.number,
                tag: "build-sound:" + event.job + ":" + event.number
            });
            notification.onclick = function () {
                try {
                    window.focus();
                } catch (e) {}
                // A new tab, so whatever the user had open here (maybe an unsaved form) is not replaced.
                window.open(buildUrl(event.job, event.number), "_blank");
                notification.close();
            };
        } catch (e) {} // some browsers throw here (for example on Android): then there is just no notification
    }

    // Asks the browser for permission. This must be called from the user's click, never on page load.
    // Older Safari answers through a callback and newer browsers through a promise: use whichever comes first.
    function requestNotificationPermission(onAnswer) {
        var answered = false;
        function finish(result) {
            if (!answered) {
                answered = true;
                onAnswer(result);
            }
        }
        try {
            var promise = window.Notification.requestPermission(finish);
            if (promise && promise.then) {
                promise.then(finish, function () {
                    finish("denied");
                });
            }
        } catch (e) {
            finish("denied");
        }
    }

    // ---- what to do with new events ----------------------------------------------------------------------

    function wantsAnything() {
        return soundEnabled() || notificationsEnabled();
    }

    function handleEvents(events) {
        var wantSound = soundEnabled() && !soundBlockedHere;
        var wantNotification = notificationsEnabled();
        // Nothing opted in, or another tab is the one that plays and notifies: stay quiet.
        if ((!wantSound && !wantNotification) || !holdsClaim()) {
            return;
        }
        events.forEach(function (event) {
            if (event.sound !== "success" && event.sound !== "problem") {
                return;
            }
            if (wantNotification) {
                showNotification(event); // notifications never need a click, so they come before the sound
            }
            if (wantSound) {
                playSound(event.sound);
            }
        });
    }

    // ---- the on/off control ------------------------------------------------------------------------------

    // A small button in the bottom right corner. It is fixed in place, so it takes no room in the page and
    // cannot push Jenkins's own layout around. It is a real <button>, so it works with Tab and Enter/Space.
    // The controls are always shown, so the user can turn the features on.
    var soundButton = null;
    var notifyButton = null;

    function styleButton(button, on) {
        button.style.cssText =
            "font: inherit; font-size: 12px; line-height: 1.2; padding: 4px 10px; border-radius: 12px; " +
            "cursor: pointer; opacity: 0.85; color: var(--text-color, #222); " +
            "background: var(--background, #fff); " +
            "border: 2px " + (on ? "solid #2e8b57" : "dashed #888") + "; font-weight: " + (on ? "600" : "400");
    }

    function refreshControls() {
        var on = soundEnabled();
        soundButton.textContent = "Build sound: " + (on ? "on" : "off");
        soundButton.setAttribute("aria-pressed", on ? "true" : "false");
        soundButton.title = on
            ? "Plays a sound when a build you started finishes. Click to turn off."
            : "Click to play a sound when a build you started finishes.";
        styleButton(soundButton, on);

        if (notifyButton) {
            var notify = notificationsEnabled();
            var blocked = notificationPermission() === "denied";
            var state = "off";
            var hint = "Click to show a notification when a build you started finishes.";
            if (notify) {
                state = "on";
                hint = "Shows a notification when a build you started finishes. Click to turn off.";
            } else if (blocked) {
                state = "blocked";
                hint = "The browser blocks notifications for this site. Allow them in the browser's site settings.";
            }
            notifyButton.textContent = "Desktop notifications: " + state;
            notifyButton.setAttribute("aria-pressed", notify ? "true" : "false");
            notifyButton.title = hint;
            styleButton(notifyButton, notify);
        }
    }

    function toggleNotifications() {
        if (notificationsEnabled()) {
            storageSet(NOTIFY_KEY, "off");
            settingsChanged();
            return;
        }
        if (notificationPermission() === "granted") {
            storageSet(NOTIFY_KEY, "on");
            settingsChanged();
            return;
        }
        if (notificationPermission() === "denied") {
            refreshControls(); // nothing we can do: only the user can unblock it in the browser settings
            return;
        }
        // Permission not asked yet: ask now, because the user just clicked.
        requestNotificationPermission(function (result) {
            if (result === "granted") {
                storageSet(NOTIFY_KEY, "on");
            }
            settingsChanged();
        });
    }

    function toggleSound() {
        storageSet(SOUND_KEY, soundEnabled() ? "off" : "on");
        primeAudio(); // this click is the moment the browser allows audio, so prepare it now
        settingsChanged();
    }

    function buildControls() {
        var box = document.createElement("div");
        box.style.cssText = "position: fixed; right: 12px; bottom: 12px; z-index: 50; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; max-width: 90vw;";
        soundButton = document.createElement("button");
        soundButton.type = "button";
        soundButton.addEventListener("click", toggleSound);
        box.appendChild(soundButton);
        if (notificationsSupported()) {
            notifyButton = document.createElement("button");
            notifyButton.type = "button";
            notifyButton.addEventListener("click", toggleNotifications);
            box.appendChild(notifyButton);
        }
        document.body.appendChild(box);
        refreshControls();
    }

    // ---- the 5 second clock ------------------------------------------------------------------------------

    var worker = null;
    var pageTimer = null;

    // A normal page timer. Browsers may slow it a lot in a hidden tab, so it is only the fallback.
    function startPageTimer(reason) {
        if (pageTimer !== null) {
            return;
        }
        console.info("[build-sound] using a page timer instead of a Web Worker (" + reason + "); " +
            "in a background tab the check may be late by up to about a minute");
        pageTimer = setInterval(tick, POLL_MS);
    }

    function startClock() {
        var thisWorker;
        try {
            thisWorker = new Worker(resourceUrl + "/build-sound-worker.js?v=" + encodeURIComponent(version));
        } catch (e) {
            startPageTimer("the Worker could not be created");
            return;
        }
        worker = thisWorker;
        worker.onmessage = tick;
        worker.onerror = function () {
            thisWorker.terminate();
            if (worker === thisWorker) {
                worker = null;
                startPageTimer("the Worker failed to run");
            }
        };
    }

    function stopClock() {
        if (worker !== null) {
            worker.terminate();
            worker = null;
        }
        if (pageTimer !== null) {
            clearInterval(pageTimer);
            pageTimer = null;
        }
    }

    // ---- start and stop ----------------------------------------------------------------------------------

    // Starts from "now": the cursor is empty, so the first request only asks where "now" is.
    function startWorking() {
        if (running) {
            return;
        }
        running = true;
        runId++;
        cursor = null;
        instance = null;
        notBefore = 0;
        waitMs = POLL_MS;
        startClock();
        poll(); // right away, so "latest" is known before the first tick
    }

    // Stops the clock and gives the claim up, so another tab can take over.
    function stopWorking() {
        if (!running) {
            return;
        }
        running = false;
        runId++;
        stopClock();
        releaseClaim();
    }

    // Looks at what the user has switched on and starts or stops accordingly.
    function applySettings() {
        if (!wrongUser && wantsAnything()) {
            if (soundEnabled()) {
                ensureSounds();
            }
            startWorking();
        } else {
            stopWorking();
        }
    }

    function settingsChanged() {
        refreshControls();
        applySettings();
    }

    // Another tab changed one of the two choices: follow it. The claim key changes every tick, so it is ignored.
    window.addEventListener("storage", function (event) {
        if (event.key === null || event.key === SOUND_KEY || event.key === NOTIFY_KEY) {
            settingsChanged();
        }
    });

    buildControls();
    applySettings();
})();
