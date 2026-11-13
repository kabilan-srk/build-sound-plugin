// Build Sound: asks Jenkins every 5 seconds whether a build started by this user has finished, and plays
// a sound and/or shows a desktop notification when one has. Loaded on every Jenkins page for logged-in users (see BuildSoundDecorator).
(function () {
    "use strict";

    // Jenkins pages that are shown inside another page would poll twice. Only the top page works.
    if (window.top !== window.self) {
        return;
    }

    var POLL_MS = 5000;
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
    var instance = null; // which Jenkins run answered last (changes when Jenkins restarts)
    var cursor = null; // the "latest" value of the last good reply; null until the first one
    var busy = false; // true while a request is in flight, so slow replies never pile up

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

    // Asks one endpoint and calls done(data) with the parsed JSON, or not at all when anything is wrong.
    // Network errors and non-JSON replies (a 403, or a redirect to the login page) are ignored on purpose:
    // the next tick simply tries again.
    function ask(path, done) {
        if (busy) {
            return;
        }
        busy = true;
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
                return response.ok ? response.text() : null;
            })
            .then(function (text) {
                var data = null;
                try {
                    data = text === null ? null : JSON.parse(text);
                } catch (e) {
                    data = null; // not JSON
                }
                if (data && typeof data.instance === "string" && typeof data.latest === "number") {
                    done(data);
                }
            })
            .catch(function () {})
            .then(function () {
                clearTimeout(timer);
                busy = false;
            });
    }

    function poll() {
        claimToPlay(); // renew our claim on every tick (see below)
        if (cursor === null) {
            // First contact (page load or after a failed start): only learn where "now" is, so an old
            // event is never replayed by a reload.
            ask("latest", function (data) {
                instance = data.instance;
                cursor = data.latest;
            });
            return;
        }
        ask("events?after=" + encodeURIComponent(cursor), function (data) {
            if (data.instance !== instance) {
                // Jenkins restarted: its event numbers start again, so our old cursor means nothing.
                // Jump to "now" and play nothing.
                instance = data.instance;
                cursor = data.latest;
                return;
            }
            if (Array.isArray(data.events) && data.events.length > 0) {
                handleEvents(data.events);
            }
            // Use "latest", not the highest event seen: events hidden from us still move "latest" on.
            cursor = data.latest;
        });
    }

    // ---- which tab plays ---------------------------------------------------------------------------------

    // Every Jenkins tab of the same browser runs this script, but only one should make a sound.
    // The tabs share localStorage, so the winner writes a "claim" there: its own tab id and the time.
    // The owner renews it on every tick; if it stops (the tab was closed) the claim gets old and another
    // tab takes over.
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

    function writeClaim() {
        storageSet(CLAIM_KEY, JSON.stringify({ tab: tabId, time: Date.now() }));
    }

    // True if this tab is the one that plays. A tab that nobody has clicked in cannot play audio (see
    // "unlock" below), so it does not compete: the tab the user actually works in wins.
    function claimToPlay() {
        if (!unlocked) {
            return false;
        }
        var claim = readClaim();
        var free = claim === null || Date.now() - claim.time > CLAIM_MS;
        if (free || claim.tab === tabId) {
            writeClaim();
            claim = readClaim(); // read back: if two tabs wrote at once, the later write won
        }
        return claim !== null && claim.tab === tabId;
    }

    // When this tab closes, give the claim up at once instead of making the others wait for it to run out.
    window.addEventListener("pagehide", function () {
        var claim = readClaim();
        if (claim && claim.tab === tabId) {
            storageRemove(CLAIM_KEY);
        }
    });

    // ---- sound -------------------------------------------------------------------------------------------

    // Browsers refuse to play audio on a page the user has not touched yet. So the first click or key press
    // on this page "unlocks" it: we start both sounds silently once, which tells the browser audio is allowed.
    var sounds = {};
    ["success", "problem"].forEach(function (name) {
        var audio = new Audio(resourceUrl + "/sounds/" + name + ".wav?v=" + encodeURIComponent(version));
        audio.preload = "auto";
        sounds[name] = audio;
    });

    var unlocked = false;

    function unlock() {
        if (unlocked) {
            return;
        }
        unlocked = true;
        document.removeEventListener("pointerdown", unlock, true);
        document.removeEventListener("keydown", unlock, true);
        Object.keys(sounds).forEach(function (name) {
            var audio = sounds[name];
            audio.muted = true;
            var started = null;
            try {
                started = audio.play();
            } catch (e) {}
            var done = function () {
                audio.pause();
                audio.currentTime = 0;
                audio.muted = false;
            };
            if (started && started.then) {
                started.then(done, function () {
                    audio.muted = false;
                });
            } else {
                done();
            }
        });
    }

    document.addEventListener("pointerdown", unlock, true);
    document.addEventListener("keydown", unlock, true);

    function playSound(name) {
        var audio = sounds[name];
        if (!audio) {
            return;
        }
        try {
            audio.currentTime = 0;
            var started = audio.play();
            if (started && started.catch) {
                started.catch(function () {}); // blocked by the browser: nothing more we can do
            }
        } catch (e) {}
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
    function requestNotificationPermission(done) {
        var answered = false;
        function finish(result) {
            if (!answered) {
                answered = true;
                done(result);
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

    function handleEvents(events) {
        var wantSound = soundEnabled();
        var wantNotification = notificationsEnabled();
        // Nothing opted in, or another tab is the one that plays and notifies: stay quiet.
        if ((!wantSound && !wantNotification) || !claimToPlay()) {
            return;
        }
        events.forEach(function (event) {
            if (event.sound !== "success" && event.sound !== "problem") {
                return;
            }
            if (wantSound) {
                playSound(event.sound);
            }
            if (wantNotification) {
                showNotification(event);
            }
        });
    }

    // ---- the on/off control ------------------------------------------------------------------------------

    // A small button in the bottom right corner. It is fixed in place, so it takes no room in the page and
    // cannot push Jenkins's own layout around. It is a real <button>, so it works with Tab and Enter/Space.
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
            notifyButton.textContent = "Desktop notifications: " + (notify ? "on" : blocked ? "blocked" : "off");
            notifyButton.setAttribute("aria-pressed", notify ? "true" : "false");
            notifyButton.title = notify
                ? "Shows a notification when a build you started finishes. Click to turn off."
                : blocked
                  ? "The browser blocks notifications for this site. Allow them in the browser's site settings."
                  : "Click to show a notification when a build you started finishes.";
            styleButton(notifyButton, notify);
        }
    }

    function toggleNotifications() {
        if (notificationsEnabled()) {
            storageSet(NOTIFY_KEY, "off");
            refreshControls();
            return;
        }
        if (notificationPermission() === "granted") {
            storageSet(NOTIFY_KEY, "on");
            refreshControls();
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
            refreshControls();
        });
    }

    function buildControls() {
        var box = document.createElement("div");
        box.style.cssText = "position: fixed; right: 12px; bottom: 12px; z-index: 50; display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; max-width: 90vw;";
        soundButton = document.createElement("button");
        soundButton.type = "button";
        soundButton.addEventListener("click", function () {
            storageSet(SOUND_KEY, soundEnabled() ? "off" : "on");
            refreshControls();
        });
        box.appendChild(soundButton);
        if (notificationsSupported()) {
            notifyButton = document.createElement("button");
            notifyButton.type = "button";
            notifyButton.addEventListener("click", toggleNotifications);
            box.appendChild(notifyButton);
        }
        document.body.appendChild(box);
        refreshControls();
        // Keep the button right when another tab changes the setting.
        window.addEventListener("storage", refreshControls);
    }

    // ---- the 5 second clock ------------------------------------------------------------------------------

    var fellBack = false;

    // A normal page timer. Browsers may slow it a lot in a hidden tab, so it is only the fallback.
    function startPageTimer(reason) {
        if (fellBack) {
            return;
        }
        fellBack = true;
        console.info("[build-sound] using a page timer instead of a Web Worker (" + reason + "); " +
            "in a background tab the check may be late by up to about a minute");
        setInterval(poll, POLL_MS);
    }

    function startClock() {
        var worker;
        try {
            worker = new Worker(resourceUrl + "/build-sound-worker.js?v=" + encodeURIComponent(version));
        } catch (e) {
            startPageTimer("the Worker could not be created");
            return;
        }
        worker.onmessage = poll;
        worker.onerror = function () {
            worker.terminate();
            startPageTimer("the Worker failed to run");
        };
    }

    buildControls();
    poll(); // right away, so "latest" is known before the first tick
    startClock();
})();
