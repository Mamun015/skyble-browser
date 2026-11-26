/* Skyble Browser — পেজে ভিডিও চলা শুরু হলে অ্যাপের নিজের প্লেয়ারে নিয়ে যায়।
   শুধু সরাসরি ভিডিও ঠিকানা (http/https) নেয়; blob/MSE, DRM বাদ।
   প্লেয়ারে বাঁ দিকে সোয়াইপে উজ্জ্বলতা, ডান দিকে সোয়াইপে ভলিউম। */
(function () {
  if (window.__bbPlayerOn) return;
  window.__bbPlayerOn = 1;

  var taken = 0;            // এক পেজে সর্বোচ্চ দুটো ভিডিও
  var WARM = 15000;         // শেষ ট্যাপের এত মিলিসেকেন্ডের মধ্যে চলা ভিডিও "ইউজারের" ধরা হয়
  var lastTap = 0;
  var tries = {};           // প্রতি ভিডিওতে কতবার চেষ্টা হয়েছে

  document.addEventListener('touchstart', function () { lastTap = Date.now(); }, true);
  document.addEventListener('mousedown', function () { lastTap = Date.now(); }, true);

  function direct(u) {
    if (!u) return '';
    u = String(u);
    if (/^(blob|data|mediasource|filesystem):/i.test(u)) return '';
    if (!/^https?:\/\//i.test(u)) return '';
    return u;
  }

  function isBlob(u) {
    return !!u && /^(blob|mediasource):/i.test(String(u));
  }

  function pickSrc(v) {
    var s = direct(v.currentSrc) || direct(v.src);
    if (s) return s;
    var ss = v.querySelectorAll('source');
    for (var i = 0; i < ss.length; i++) {
      var x = direct(ss[i].src);
      if (x) return x;
    }
    return '';
  }

  function take(v) {
    if (!v || taken >= 2 || v.__bbHandled) return;
    if (v.muted || v.volume === 0) return;
    var d = v.duration;
    if (isFinite(d) && d < 3) return;      // ছোট ক্লিপ/বিজ্ঞাপন বাদ; লাইভ স্ট্রিম (Infinity) চলবে
    var r = v.getBoundingClientRect();
    if (r.width < 120 || r.height < 68) return;
    var u = pickSrc(v);
    // blob: হলে পেজে আসল ঠিকানা থাকে না; অ্যাপ নিজের নেটওয়ার্ক স্নিফার থেকে বেছে নেয়
    var blob = !u && (isBlob(v.currentSrc) || isBlob(v.src));
    if (!u && !blob) return;
    if (!window.BBPlayer || !window.BBPlayer.open) return;
    if (Date.now() - lastTap > WARM) return;

    v.__bbHandled = 1;
    try { v.pause(); } catch (e) { }
    var ok = false;
    try { ok = window.BBPlayer.open(blob ? 'blob:' : u, document.title || ''); } catch (e) { }
    if (ok) taken++;
    else v.__bbHandled = 0;
  }

  /* ভিডিও আগে muted থাকতে পারে, আকার বা duration পরে আসতে পারে — তাই কয়েকবার চেষ্টা */
  function tryLater(v) {
    var n = tries[v.__bbId] || 0;
    if (n >= 4 || v.__bbHandled) return;
    tries[v.__bbId] = n + 1;
    setTimeout(function () { take(v); }, [200, 700, 1500, 3000][n]);
  }

  function onVideo(v) {
    if (!v.__bbId) v.__bbId = String(Math.random()).slice(2);
    v.__bbWasPlaying = 1;
    tryLater(v);
  }

  document.addEventListener('play', function (e) {
    var v = e.target;
    if (v && v.tagName === 'VIDEO') onVideo(v);
  }, true);

  document.addEventListener('playing', function (e) {
    var v = e.target;
    if (v && v.tagName === 'VIDEO') onVideo(v);
  }, true);

  document.addEventListener('loadedmetadata', function (e) {
    var v = e.target;
    if (v && v.tagName === 'VIDEO' && !v.paused) onVideo(v);
  }, true);
})();
