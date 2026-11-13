// A Web Worker whose only job is to be a clock.
// Why a worker: browsers slow a normal page timer to about once a minute in a hidden tab, but a worker's timer
// keeps running on time. The worker does no network calls; it just tells the page "now" every 5 seconds, and
// the page (build-sound.js) does the request.
setInterval(function () {
    postMessage("tick");
}, 5000);
