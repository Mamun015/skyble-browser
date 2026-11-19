/* Skyble Browser — Google-এর ফ্রি অনুবাদ।
   ইংরেজি ছাড়া অন্য ভাষার পেজ নিজে থেকেই অনুবাদ হয় (অটো চালু থাকলে),
   অটো বন্ধ থাকলে নিচে "অনুবাদ" বোতাম ভেসে ওঠে। ভাষা আসে __bbTrCfg থেকে। */
(function () {
  if (window.__bbTrOn) return;
  window.__bbTrOn = 1;

  var C = window.__bbTrCfg || {};
  var TL = String(C.tl || 'bn');
  var AUTO = !!C.auto;
  var TNAME = String(C.name || TL);

  function apiUrl() {
    return 'https://translate.googleapis.com/translate_a/single' +
           '?client=gtx&dt=t&sl=auto&tl=' + encodeURIComponent(TL) + '&q=';
  }
  var MAX_CHARS = 1600;      // এক অনুরোধে এত অক্ষর
  var WORKERS = 3;           // একসাথে কত অনুরোধ
  var MAX_BATCHES = 400;

  var SKIP = {
    SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, CODE: 1, PRE: 1, KBD: 1, SAMP: 1,
    TEXTAREA: 1, INPUT: 1, SELECT: 1, OPTION: 1, IFRAME: 1, CANVAS: 1,
    SVG: 1, VIDEO: 1, AUDIO: 1
  };

  var seen = 0, done = 0, failed = 0;
  var stopped = false, translating = false, translated = false;
  var totalBatches = 0, doneBatches = 0, shownPercent = -1;

  /* ---------------------------------------------------------------- pill */

  var pill = null, pillTimer = null, pillHideTimer = null;

  function clearTimers() {
    if (pillTimer) { clearTimeout(pillTimer); pillTimer = null; }
    if (pillHideTimer) { clearTimeout(pillHideTimer); pillHideTimer = null; }
  }

  function showPill(text, actionLabel, onAction, closable) {
    clearTimers();
    if (!pill) {
      pill = document.createElement('div');
      pill.setAttribute('data-bb-no', '1');
      pill.style.cssText = 'position:fixed;left:12px;bottom:76px;z-index:2147483647;' +
        'display:flex;align-items:center;max-width:80vw;' +
        'background:#006A4E;color:#fff;font:600 12.5px/1.3 sans-serif;' +
        'padding:8px 10px 8px 13px;border-radius:999px;' +
        'box-shadow:0 4px 16px rgba(0,0,0,.35)';
      (document.body || document.documentElement).appendChild(pill);
    }
    pill.innerHTML = '';
    pill.style.display = 'flex';

    var t = document.createElement('span');
    t.textContent = text;
    pill.appendChild(t);

    if (actionLabel) {
      var b = document.createElement('button');
      b.type = 'button';
      b.setAttribute('data-bb-no', '1');
      b.textContent = actionLabel;
      b.style.cssText = 'border:0;margin-left:8px;background:rgba(255,255,255,.24);' +
        'color:#fff;font:700 12px sans-serif;padding:5px 10px;border-radius:999px';
      b.onclick = function (e) {
        e.preventDefault();
        e.stopPropagation();
        if (onAction) onAction();
      };
      pill.appendChild(b);
    }

    if (closable) {
      var x = document.createElement('button');
      x.type = 'button';
      x.setAttribute('data-bb-no', '1');
      x.textContent = '✕';
      x.style.cssText = 'border:0;margin-left:6px;background:transparent;color:#fff;' +
        'font:700 13px sans-serif;padding:4px 6px;border-radius:999px';
      x.onclick = function (e) {
        e.preventDefault();
        e.stopPropagation();
        hidePill(0);
      };
      pill.appendChild(x);
    }
  }

  function hidePill(delay) {
    if (!pill) return;
    clearTimers();
    var p = pill;
    var fn = function () {
      if (p && p.parentNode) p.parentNode.removeChild(p);
      if (pill === p) pill = null;
    };
    if (!delay) { fn(); return; }
    pillTimer = setTimeout(fn, delay);
  }

  /* ------------------------------------------------------ ভাষা চেনা */

  function metaLang() {
    var ms = document.getElementsByTagName('meta');
    for (var i = 0; i < ms.length; i++) {
      var key = (ms[i].getAttribute('http-equiv') || ms[i].getAttribute('name') || '')
        .toLowerCase();
      if (key === 'content-language' || key === 'language') {
        var v = (ms[i].getAttribute('content') || '').toLowerCase();
        if (v) return v;
      }
    }
    return '';
  }

  function base(code) {
    if (!code) return '';
    var c = String(code).toLowerCase().split(/[-_]/)[0].replace(/[^a-z]/g, '');
    if (c === 'iw') c = 'he';
    if (c === 'in') c = 'id';
    if (c === 'jw') c = 'jv';
    if (c === 'ji') c = 'yi';
    return c;
  }

  function sameLang(a, b) {
    return !!a && !!b && base(a) === base(b);
  }

  /** পেজের ভাষা — আগে html lang, না পেলে লেখা দেখে আন্দাজ */
  function detect() {
    var t = (document.documentElement.getAttribute('lang') || '').toLowerCase();
    if (!t) t = metaLang();
    t = base(t);

    var sample = '';
    try {
      var body = document.body;
      sample = body ? (body.innerText || body.textContent || '') : '';
    } catch (e) { }
    sample = sample.slice(0, 3000);

    var latin = 0, bengali = 0, other = 0;
    for (var i = 0; i < sample.length; i++) {
      var c = sample.charCodeAt(i);
      if (c < 9) continue;
      if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 0xC0 && c <= 0x24F)) latin++;
      else if (c >= 0x0980 && c <= 0x09FF) bengali++;
      else if (c > 0x02FF) other++;
    }
    var latinOnly = latin > 0 && bengali === 0 && other === 0;

    if (!t || t === 'und') {
      if (bengali > latin && bengali > other) t = 'bn';
      else if (latinOnly) t = 'en';
    }
    return { code: t, latinOnly: latinOnly };
  }

  /* ------------------------------------------------------ অনুবাদ */

  function usable(node) {
    var s = node.nodeValue;
    if (!s) return false;
    s = s.replace(/\s+/g, ' ').trim();
    if (s.length < 2 || s.length > 4000) return false;
    if (!/[A-Za-z\u00C0-\u024F\u0370-\uFFFF]/.test(s)) return false;
    return true;
  }

  function skipped(node) {
    var p = node.parentNode;
    if (!p || p.nodeType !== 1) return true;
    if (SKIP[p.nodeName.toUpperCase()]) return true;
    if (p.isContentEditable) return true;
    if (p.getAttribute && p.getAttribute('data-bb-no')) return true;
    if (p.closest && p.closest('[translate="no"],[data-bb-no]')) return true;
    return false;
  }

  function collect(root, out) {
    if (!root) return out;
    var walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
    var n = walk.nextNode();
    while (n) {
      if (!n.__bbSeen && !skipped(n) && usable(n)) {
        n.__bbSeen = 1;
        out.push(n);
      }
      n = walk.nextNode();
    }
    return out;
  }

  function parse(body, lines) {
    var d = JSON.parse(body);
    var segs = d && d[0];
    if (!segs || !segs.length) throw new Error('shape');
    var flat = '';
    for (var i = 0; i < segs.length; i++) flat += (segs[i][0] || '');
    if (flat.split('\n').length !== lines) {
      flat = segs.map(function (x) {
        return String(x[0] || '').replace(/\n+$/, '');
      }).join('\n');
    }
    return flat;
  }

  // সাইটের CSP fetch আটকালে অ্যাপের ভেতরকার ব্রিজ দিয়ে আনা হয়
  var bridged = false;

  function viaBridge(url, lines) {
    if (!(window.BBTranslate && window.BBTranslate.get)) throw new Error('no bridge');
    var body = window.BBTranslate.get(url);
    if (!body) throw new Error('bridge empty');
    return parse(body, lines);
  }

  function translate(text) {
    var url = apiUrl() + encodeURIComponent(text);
    var lines = text.split('\n').length;
    if (bridged) {
      try { return Promise.resolve(viaBridge(url, lines)); }
      catch (e) { return Promise.reject(e); }
    }
    return fetch(url, { credentials: 'omit' })
      .then(function (r) {
        if (!r.ok) throw new Error('http ' + r.status);
        return r.text();
      })
      .then(function (body) { return parse(body, lines); })
      .catch(function () {
        bridged = true;
        return viaBridge(url, lines);
      });
  }

  function batches(nodes) {
    var list = [], cur = [], curText = [], len = 0;
    for (var i = 0; i < nodes.length; i++) {
      var raw = nodes[i].nodeValue;
      var s = raw.replace(/\s+/g, ' ').trim();
      if (!s) continue;
      if (cur.length && len + s.length + 1 > MAX_CHARS) {
        list.push({ nodes: cur, text: curText.join('\n') });
        cur = []; curText = []; len = 0;
      }
      cur.push(nodes[i]);
      curText.push(s);
      len += s.length + 1;
    }
    if (cur.length) list.push({ nodes: cur, text: curText.join('\n') });
    return list;
  }

  function progress() {
    if (!totalBatches) return;
    var p = Math.round(doneBatches * 100 / totalBatches);
    if (p === shownPercent) return;
    shownPercent = p;
    showPill(TNAME + ' ভাষায় অনুবাদ হচ্ছে… ' + p + '%');
  }

  function applyBatch(b) {
    return translate(b.text).then(function (res) {
      var parts = res.split('\n');
      if (parts.length !== b.nodes.length) { failed += 1; return; }
      for (var i = 0; i < b.nodes.length; i++) {
        var node = b.nodes[i];
        if (!node.parentNode) continue;
        var src = node.nodeValue;
        var lead = (/^\s*/.exec(src) || [''])[0];
        var trail = (/\s*$/.exec(src) || [''])[0];
        if (node.__bbOrig === undefined) node.__bbOrig = src;
        node.nodeValue = lead + parts[i].trim() + trail;
      }
      done += b.nodes.length;
    }).catch(function () {
      failed += 1;
      if (failed > 8) stopped = true;
    }).then(function () {
      doneBatches += 1;
      progress();
    });
  }

  function run(root) {
    var nodes = collect(root || document.body, []);
    var left = MAX_BATCHES - seen;
    if (left <= 0) return Promise.resolve(false);
    var list = batches(nodes).slice(0, left);
    if (!list.length) return Promise.resolve(false);
    seen += list.length;
    totalBatches += list.length;

    var idx = 0;
    function worker() {
      if (stopped || idx >= list.length) return Promise.resolve();
      var b = list[idx++];
      return applyBatch(b).then(worker);
    }
    var all = [];
    for (var w = 0; w < WORKERS; w++) all.push(worker());
    return Promise.all(all).then(function () { return true; });
  }

  function translateAll() {
    if (translating) return;
    // মেনু থেকে ভাষা বদলে দিলেও যাতে নতুন ভাষাতেই হয়
    var now = window.__bbTrCfg || {};
    if (now.tl) TL = String(now.tl);
    if (now.name) TNAME = String(now.name);
    translating = true;
    translated = false;
    stopped = false;
    shownPercent = -1;
    showPill(TNAME + ' ভাষায় অনুবাদ হচ্ছে… 0%');
    run(document.body).then(function (ran) {
      if (!ran && done === 0) {
        translating = false;
        showPill('অনুবাদ করার মতো লেখা নেই');
        hidePill(2500);
        return;
      }
      translating = false;
      translated = done > 0;
      if (translated) {
        showPill('✓ ' + TNAME + ' ভাষায় অনুবাদ হয়েছে', 'মূল', untranslate, false);
        pillTimer = setTimeout(function () { hidePill(0); }, 7000);
        watchChanges();
      } else {
        showPill('অনুবাদ করা যায়নি');
        hidePill(2500);
      }
    });
  }

  function untranslate() {
    var walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var n = walk.nextNode();
    while (n) {
      if (n.__bbOrig !== undefined) {
        n.nodeValue = n.__bbOrig;
        n.__bbOrig = undefined;
        n.__bbSeen = 0;
      }
      n = walk.nextNode();
    }
    translated = false;
    translating = false;
    stopped = false;
    seen = 0; done = 0; failed = 0; totalBatches = 0; doneBatches = 0;
    hidePill(0);
    return true;
  }

  /* নতুন আসা লেখাও অনুবাদ হয় (অনন্ত স্ক্রল, কমেন্ট ইত্যাদি) */
  var timer = null;
  var watched = false;

  function schedule() {
    if (!translated || timer) return;
    timer = setTimeout(function () {
      timer = null;
      if (translated) run(document.body);
    }, 900);
  }

  function watchChanges() {
    if (watched || !window.MutationObserver) return;
    watched = true;
    try {
      new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var r = records[i];
          if (r.target && r.target.getAttribute &&
              r.target.getAttribute('data-bb-no')) continue;
          if (r.addedNodes && r.addedNodes.length) { schedule(); return; }
        }
      }).observe(document.body, { childList: true, subtree: true });
    } catch (e) { }
  }

  function offer() {
    showPill('🌐 ' + TNAME + ' ভাষায় অনুবাদ?', 'অনুবাদ', translateAll, true);
    pillTimer = setTimeout(function () { hidePill(0); }, 15000);
  }

  /* অ্যাপের মেনু থেকেও ডাকা যায় */
  window.__bbTranslateNow = function () {
    if (translating) return;
    if (translated) return;
    translateAll();
  };
  window.__bbOriginal = function () { return untranslate(); };
  window.__bbTranslated = function () { return translated; };

  function boot() {
    if (window.__bbBooted) return;
    window.__bbBooted = 1;
    var d = detect();
    // পেজটা আগেই অনুবাদের ভাষাতেই আছে
    if (sameLang(d.code, TL)) return;
    if (AUTO) {
      // ইংরেজি পেজ নিজে থেকে অনুবাদ হয় না; বাকি সব ভাষা হয়
      if (sameLang(d.code, 'en')) return;
      translateAll();
    } else {
      offer();
    }
  }

  if (document.body) boot();
  else document.addEventListener('DOMContentLoaded', boot, false);
})();
