/* Session timer: warns before the sign-in ends, offers "Stay signed in", then goes to the signed-out page.
   Times come from the server (audit.ui.session-timeout-minutes, audit.ui.session-warning-seconds). Any page request or htmx call restarts the clock. */
(function () {
  'use strict';
  var root = document.getElementById('acc-session');
  var DRAFTS = 'acc-drafts';

  function csrf() {
    var t = document.querySelector('meta[name="_csrf"]'), h = document.querySelector('meta[name="_csrf_header"]');
    return t && h ? { name: h.content, value: t.content } : null;
  }

  // text typed into a decision box survives a sign-out on this device and comes back after signing in again
  function restoreDrafts() {
    try {
      var raw = localStorage.getItem(DRAFTS);
      if (!raw) return;
      var d = JSON.parse(raw), here = location.pathname + location.search;
      if (!d || d.path !== here) return;
      Object.keys(d.fields || {}).forEach(function (n) {
        var el = document.querySelector('textarea[name="' + n.replace(/"/g, '') + '"]');
        if (el && !el.value) el.value = d.fields[n];
      });
      localStorage.removeItem(DRAFTS);
    } catch (e) { /* storage may be blocked: nothing to restore */ }
  }
  function saveDrafts() {
    try {
      var fields = {}, any = false;
      document.querySelectorAll('textarea[name]').forEach(function (el) { if (el.value.trim()) { fields[el.name] = el.value; any = true; } });
      if (any) localStorage.setItem(DRAFTS, JSON.stringify({ path: location.pathname + location.search, fields: fields }));
    } catch (e) { /* ignore */ }
  }
  restoreDrafts();
  if (!root) return;

  var timeout = parseInt(root.dataset.timeout, 10) * 1000, warn = parseInt(root.dataset.warn, 10) * 1000;
  var deadline = Date.now() + timeout, shown = false, lastTouch = Date.now();
  var title = root.querySelector('#acc-s-title'), text = root.querySelector('#acc-s-text'), stay = root.querySelector('#acc-stay');

  function span(ms) {
    var s = Math.max(1, Math.ceil(ms / 1000));
    if (s >= 90) { var m = Math.round(s / 60); return (m === 1 ? root.dataset.tMin1 : root.dataset.tMin).replace('{0}', m); }
    return root.dataset.tSec.replace('{0}', s);
  }
  function show() {
    if (!shown) { shown = true; root.hidden = false; stay.focus(); }
    var left = deadline - Date.now(), idle = Math.round((timeout - left) / 60000);
    title.textContent = root.dataset.tTitle.replace('{0}', span(left));
    text.textContent = root.dataset.tIdle.replace('{0}', idle < 1 ? '< 1' : idle);
  }
  function hide() { shown = false; root.hidden = true; }

  function tick() {
    var left = deadline - Date.now();
    if (left <= 0) {
      saveDrafts();
      location.href = root.dataset.out + '?from=' + encodeURIComponent(location.pathname + location.search);
    } else if (left <= warn) show();
  }
  setInterval(tick, 1000);
  document.addEventListener('visibilitychange', function () { if (!document.hidden) tick(); });

  // a request to the server keeps the session, so the clock restarts (htmx calls only; the dialog's own button asks explicitly)
  document.body.addEventListener('htmx:afterRequest', function (e) { if (!shown && e.detail.successful) deadline = Date.now() + timeout; });

  stay.addEventListener('click', function () {
    var c = csrf(), h = { 'X-Requested-With': 'fetch' };
    if (c) h[c.name] = c.value;
    fetch(root.dataset.keepalive, { method: 'POST', headers: h, credentials: 'same-origin' }).then(function (r) {
      if (r.ok) { deadline = Date.now() + timeout; hide(); } else { location.href = root.dataset.out; }
    }).catch(function () { /* offline: the timer carries on */ });
  });
})();
