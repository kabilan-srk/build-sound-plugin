// Build Sound: asks Jenkins every 5 seconds whether a build started by this user has finished.
// Loaded on every Jenkins page for logged-in users (see BuildSoundDecorator).
(function () {
    "use strict";

    var POLL_MS = 5000;

    // The values Jenkins gave us in the data- attributes of our own <script> tag.
    var script = document.currentScript;
    if (!script) {
        return;
    }
    var rootUrl = script.getAttribute("data-root-url") || "";
    var resourceUrl = script.getAttribute("data-resource-url") || "";
    var version = script.getAttribute("data-version") || "";

    // What we remember between polls.
    var instance = null; // which Jenkins run answered last (changes when Jenkins restarts)
    var cursor = null; // the "latest" value of the last good reply; null until the first one
    var busy = false; // true while a request is in flight, so slow replies never pile up

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
            if (Array.isArray(data.events)) {
                data.events.forEach(handleEvent);
            }
            // Use "latest", not the highest event seen: events hidden from us still move "latest" on.
            cursor = data.latest;
        });
    }

    // For now an event is only written to the console. Job names are only ever used as plain text.
    function handleEvent(event) {
        console.log("[build-sound] event", event.sound, event.job, event.number);
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

    poll(); // right away, so "latest" is known before the first tick
    startClock();
})();
