/* Explore page: Find bar, filter chips, time range, group by, chart zoom, event panel, saved searches.
   The server renders every filter into div.ex-state[data-filter]; this script keeps that state, sends it to /audit/rows and mirrors it in the address bar. */
(function () {
  'use strict';
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var main = $('#main');
  if (!main || !$('#auditSection')) return;
  var S = {};
  var sel = null;                      // id of the event shown in the panel
  var forcePanel = false;
  var MSG = main.dataset;

  /* ── state ─────────────────────────────────────────────────────────────── */
  function readState() {
    var el = $('#auditSection .ex-state');
    if (!el) return;
    try { S = JSON.parse(el.dataset.filter || '{}'); } catch (e) { S = {}; }
    var rb = $('#rangeBtn'); if (rb && el.dataset.rangeLabel) rb.textContent = el.dataset.rangeLabel;
  }

  function clean(o) {
    var r = {};
    Object.keys(o).forEach(function (k) { var v = o[k]; if (v !== '' && v != null && v !== false) r[k] = String(v); });
    return r;
  }

  /* change: key -> value ('' removes). Page goes back to 1 unless the change names a page. */
  function load(change, replaceAll) {
    var next = replaceAll ? {} : Object.assign({}, S);
    delete next.open;
    Object.keys(change).forEach(function (k) { next[k] = change[k]; });
    if (!('page' in change)) next.page = '1';
    next = clean(next);
    if (!next.size) next.size = '20';
    if (!next.sort) next.sort = '-date';
    if (!next.range && !next.since && !next.dateFrom && !next.dateTo) next.range = 'all';
    S = next;
    fetchRows();
  }

  function fetchRows() {
    var el = $('#auditSection');
    el.classList.add('htmx-request');
    htmx.ajax('GET', '/audit/rows', { target: '#auditSection', swap: 'innerHTML', values: S });
  }

  var URL_SKIP = { page: '1', size: '20', sort: '-date', groupBy: 'none', tab: 'all', open: null };
  function syncUrl() {
    var p = new URLSearchParams();
    Object.keys(S).forEach(function (k) {
      if (k === 'open') return;
      if (URL_SKIP[k] !== undefined && S[k] === URL_SKIP[k]) return;
      p.set(k, S[k]);
    });
    var str = p.toString();
    if (str === 'range=24h') str = '';
    try { history.replaceState(null, '', location.pathname + (str ? '?' + str : '')); } catch (e) { /* no history in a sandbox */ }
  }

  document.body.addEventListener('htmx:afterSwap', function (e) {
    if (e.detail.target && e.detail.target.id === 'auditSection') {
      e.detail.target.classList.remove('htmx-request');
      readState(); syncUrl(); afterRows();
    }
  });
  document.body.addEventListener('htmx:responseError', function (e) {
    if (e.detail.target && e.detail.target.id === 'auditSection') e.detail.target.classList.remove('htmx-request');
  });

  /* ── after every table swap: keep or choose the selected event ───────────── */
  function afterRows() {
    var rows = $$('#auditTbody tr.ex-row:not(.ex-child)');
    var want = sel && $('#row-' + cssId(sel));
    if (!want && rows.length) want = rows[0];
    var force = forcePanel; forcePanel = false;
    if (want) selectRow(want, force || !(want.dataset.id === sel && $('#eventPanel .px')));
    else { sel = null; $('#eventPanel').innerHTML = '<div class="px-empty">' + (MSG.mEmpty || '') + '</div>'; }
  }
  function cssId(id) { return String(id).replace(/[^A-Za-z0-9_-]/g, function (c) { return '\\' + c; }); }

  function selectRow(tr, loadPanel) {
    $$('#auditTbody tr.selected').forEach(function (x) { x.classList.remove('selected'); x.setAttribute('aria-selected', 'false'); });
    tr.classList.add('selected'); tr.setAttribute('aria-selected', 'true');
    sel = tr.dataset.id;
    if (loadPanel !== false) loadPanelFor(sel);
  }
  function loadPanelFor(id) {
    htmx.ajax('GET', '/audit/panel/' + encodeURIComponent(id), { target: '#eventPanel', swap: 'innerHTML' });
  }

  /* ── Find bar ─────────────────────────────────────────────────────────── */
  var KEYMAP = { patient: 'q', q: 'q', actor: 'agent', agent: 'agent', person: 'agent', practitioner: 'agent', system: 'client', submitted: 'client', client: 'client', by: 'client',
    facility: 'facility', site: 'facility', encounter: 'encounter', visit: 'encounter', department: 'department', dept: 'department', ward: 'department',
    purpose: 'purpose', action: 'action', outcome: 'outcome', result: 'result', range: 'range', from: 'dateFrom', to: 'dateTo' };

  function parseFind(text) {
    var out = {}, free = [];
    var parts = text.trim().split(/\s+(?=(?:[\w-]+\s*(?:!=|=)|break-?glass\b|btg\b|incomplete\b|no[ -]link))/i);
    parts.forEach(function (part) {
      var m = /^([\w-]+)\s*(!=|=)\s*(.+)$/.exec(part);
      if (m && KEYMAP[m[1].toLowerCase()]) {
        var k = KEYMAP[m[1].toLowerCase()], neg = m[2] === '!=', v = m[3].trim().replace(/^"(.*)"$/, '$1');
        if (k === 'outcome') {
          var low = v.toLowerCase();
          if (low === 'ok' || low === '0' || low === 'success') out.result = neg ? 'failed' : 'ok';
          else if (low === 'failed' || low === 'fail') out.result = neg ? 'ok' : 'failed';
          else out.outcome = v;
        } else if (k === 'purpose') out.purpose = v.toUpperCase();
        else if (k === 'action') out.action = v.charAt(0).toUpperCase();
        else out[k] = v;
        return;
      }
      var low2 = part.trim().toLowerCase();
      if (/^(break-?glass|btg)$/.test(low2)) { out.breakGlass = 'true'; return; }
      if (low2 === 'incomplete') { out.incomplete = 'true'; return; }
      if (/^no[ -]link/.test(low2)) { out.link = 'none'; return; }
      free.push(part.trim());
    });
    return { filters: out, free: free.join(' ').trim() };
  }

  function submitFind(text) {
    var r = parseFind(text);
    var ch = Object.assign({ q: '' }, r.filters);
    if (!r.free) { load(ch); return; }
    fetch('/find/suggest?q=' + encodeURIComponent(r.free), { headers: { 'Accept': 'application/json' }, credentials: 'same-origin' })
      .then(function (x) { return x.json(); })
      .then(function (j) {
        var hit = null;
        (j.groups || []).some(function (g) { return (g.items || []).some(function (it) { if (it.filter) { hit = it; return true; } return false; }); });
        if (hit) load(Object.assign(ch, hit.filter));
        else { ch.q = r.free; load(ch); toast(MSG.mNomatch, 'ok'); }
      })
      .catch(function () { ch.q = r.free; load(ch); });
  }

  $('#findForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var v = $('#searchInput').value;
    if (!v.trim()) { load({ q: '' }); return; }
    submitFind(v);
  });

  /* ── menus ─────────────────────────────────────────────────────────────── */
  function closeMenus(except) {
    $$('.menu').forEach(function (m) { if (m !== except) m.hidden = true; });
    $$('#rangeBtn,#addBtn').forEach(function (b) { b.setAttribute('aria-expanded', 'false'); });
  }
  function toggleMenu(btn, menu) {
    var open = menu.hidden;
    closeMenus();
    menu.hidden = !open;
    btn.setAttribute('aria-expanded', open ? 'true' : 'false');
    return open;
  }

  /* ── clicks (delegated, because the fragment is replaced on every search) ── */
  document.addEventListener('click', function (e) {
    var t = e.target;
    if (!t.closest('.menu-wrap')) closeMenus();

    if (t.closest('#rangeBtn')) { toggleMenu($('#rangeBtn'), $('#rangeMenu')); return; }
    var rg = t.closest('#rangeMenu [data-range]');
    if (rg) { closeMenus(); load({ range: rg.dataset.range, since: '', until: '', dateFrom: '', dateTo: '' }); return; }
    if (t.closest('#dateApply')) {
      var a = $('#dateFrom').value, b = $('#dateTo').value;
      if (!a && !b) return;
      closeMenus(); load({ range: '', since: '', until: '', dateFrom: a, dateTo: b }); return;
    }

    if (t.closest('#addBtn')) {
      if (toggleMenu($('#addBtn'), $('#addMenu'))) { $('#addPick').hidden = false; $('#addForm').hidden = true; }
      return;
    }
    var fld = t.closest('#addPick [data-field]');
    if (fld) { pickField(fld); return; }
    if (t.closest('#addBack')) { $('#addPick').hidden = false; $('#addForm').hidden = true; return; }

    var chipBtn = t.closest('.ex-chip [data-act]');
    if (chipBtn) {
      var key = chipBtn.closest('.ex-chip').dataset.chip, act = chipBtn.dataset.act;
      if (act === 'remove') {
        var c = {}; c[key] = '';
        if (key === 'tab') c[key] = 'all';
        load(c);
      } else if (act === 'cycle') {
        var order = { '': 'ok', ok: 'failed', failed: '' };
        load({ result: order[S.result || ''] });
      }
      return;
    }

    var grp = t.closest('[data-group]');
    if (grp && grp.closest('.ex-groupby')) { load({ groupBy: grp.dataset.group }); return; }

    var tr = t.closest('button.try');
    if (tr) {
      if (tr.dataset.query != null && tr.dataset.query !== '') {
        var o = {}; new URLSearchParams(tr.dataset.query).forEach(function (v, k) { o[k] = v; });
        if (!o.range && !o.dateFrom && !o.dateTo && !o.since) o.range = 'all';
        o.size = S.size || '20';
        load(o, true);
      } else if (tr.dataset.apply) {
        var ch = JSON.parse(tr.dataset.apply);
        if (ch.facility) ch.facility = ch.facility;
        load(ch);
      }
      return;
    }

    if (t.closest('#saveBtn')) { $('#saveForm').hidden = false; $('#saveName').focus(); return; }
    if (t.closest('#saveCancel')) { $('#saveForm').hidden = true; return; }

    var pg = t.closest('.page-btn');
    if (pg && pg.dataset.page && !pg.classList.contains('disabled') && !pg.disabled) { load({ page: pg.dataset.page }); return; }

    var th = t.closest('thead th.sortable[data-sort]');
    if (th) { load({ sort: (S.sort || '-date') === '-date' ? 'date' : '-date' }); return; }

    var ex = t.closest('.ex-expand');
    if (ex) {
      var n = ex.dataset.expand, kids = $$('#auditTbody .ex-g' + n), shown = ex.dataset.open === '1';
      kids.forEach(function (k) { k.hidden = shown; });
      ex.dataset.open = shown ? '0' : '1';
      ex.textContent = shown ? ex.dataset.label : MSG.mHide;
      return;
    }

    var fv = t.closest('.ex-t .fv');
    if (fv && fv.dataset.v) { var cc = {}; cc[fv.dataset.k] = fv.dataset.v; load(cc); return; }

    var gr = t.closest('tr.ex-grow');
    if (gr && gr.dataset.fval) { var gc = { groupBy: 'none' }; gc[gr.dataset.fkey] = gr.dataset.fval; load(gc); return; }

    var row = t.closest('#auditTbody tr.ex-row');
    if (row && !t.closest('a,button')) { selectRow(row); return; }

    if (t.closest('#panelClose')) { $('#eventPanel').innerHTML = '<div class="px-empty">' + (MSG.mEmpty || '') + '</div>'; sel = null; $$('#auditTbody tr.selected').forEach(function (x) { x.classList.remove('selected'); }); return; }

    var rv = t.closest('[data-review]');
    if (rv) { markReview(rv.dataset.id, rv.dataset.review); return; }
    var raw = t.closest('[data-raw]');
    if (raw) { openDrawer(raw.dataset.id); return; }

    if (t.closest('#exportBtn')) {
      var v = Object.assign({}, S); delete v.page; delete v.size; delete v.open; delete v.groupBy;
      window.location = '/audit/export.csv?' + new URLSearchParams(v).toString(); return;
    }
    if (t.closest('#verifyBtn')) { verifyChain(); return; }
    if (t.closest('#drawerOverlay')) closeDrawer();
  });

  document.addEventListener('change', function (e) {
    if (e.target.id === 'pageSizeSelect') load({ size: e.target.value });
  });

  /* ── Add filter ───────────────────────────────────────────────────────── */
  var addField = null;
  function pickField(btn) {
    var kind = btn.dataset.kind, key = btn.dataset.field;
    if (kind === 'flag') { closeMenus(); var c = {}; c[key] = 'true'; load(c); return; }
    addField = key;
    $('#addPick').hidden = true; $('#addForm').hidden = false;
    $('#addLabel').textContent = btn.textContent;
    var inp = $('#addInput'), sl = $('#addSelect');
    if (kind === 'select') {
      sl.innerHTML = '';
      btn.dataset.options.split('|').forEach(function (o) {
        var i = o.indexOf('='), op = document.createElement('option'); op.value = o.slice(0, i); op.textContent = o.slice(i + 1); sl.appendChild(op);
      });
      sl.hidden = false; inp.hidden = true; sl.focus();
    } else {
      inp.hidden = false; sl.hidden = true; inp.value = ''; inp.placeholder = btn.dataset.ph || ''; inp.focus();
    }
  }
  document.addEventListener('submit', function (e) {
    if (e.target.id === 'addForm') {
      e.preventDefault();
      var v = $('#addSelect').hidden ? $('#addInput').value.trim() : $('#addSelect').value;
      if (!v || !addField) return;
      closeMenus();
      var c = {}; c[addField] = v; load(c);
    } else if (e.target.id === 'saveForm') {
      e.preventDefault(); saveSearch();
    }
  });

  /* ── Save this search ─────────────────────────────────────────────────── */
  function csrfHeaders() {
    var t = $('meta[name="_csrf"]'), h = $('meta[name="_csrf_header"]'), o = {};
    if (t && h) o[h.content] = t.content;
    return o;
  }
  function saveSearch() {
    var name = $('#saveName').value.trim();
    var q = Object.assign({}, S); delete q.page; delete q.size; delete q.open;
    var body = new URLSearchParams({ name: name, query: new URLSearchParams(q).toString() });
    fetch('/explore/saved', { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded' }, csrfHeaders()), body: body, credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('x'); return r.json(); })
      .then(function () {
        $('#saveForm').hidden = true; $('#saveName').value = '';
        toast(MSG.mSaved, 'ok');
        var row = $('.ex-tryrow'), b = document.createElement('button');
        b.type = 'button'; b.className = 'try try-saved'; b.textContent = name; b.dataset.query = new URLSearchParams(q).toString();
        row.insertBefore(b, $('.ex-hint'));
      })
      .catch(function () { toast(MSG.mSavefail, 'err'); });
  }

  /* ── chart: click a bar, or drag over several, to zoom to that time ───────── */
  var dragFrom = null, dragTo = null, justDragged = false;
  function bars() { return $$('.xbars .xbar'); }
  function paint() {
    var all = bars(), a = Math.min(dragFrom, dragTo), b = Math.max(dragFrom, dragTo);
    all.forEach(function (x, i) { x.classList.toggle('sel', dragFrom != null && i >= a && i <= b); });
  }
  document.addEventListener('mousedown', function (e) {
    var bar = e.target.closest && e.target.closest('.xbar');
    if (!bar || e.button !== 0) return;
    dragFrom = dragTo = bars().indexOf(bar); paint(); e.preventDefault();
  });
  document.addEventListener('mouseover', function (e) {
    if (dragFrom == null) return;
    var bar = e.target.closest && e.target.closest('.xbar');
    if (bar) { dragTo = bars().indexOf(bar); paint(); }
  });
  document.addEventListener('mouseup', function () {
    if (dragFrom == null) return;
    var all = bars(), a = Math.min(dragFrom, dragTo), b = Math.max(dragFrom, dragTo);
    var moved = a !== b;
    dragFrom = dragTo = null;
    if (moved && all[a] && all[b]) { justDragged = true; zoom(all[a].dataset.from, all[b].dataset.to); }
    else all.forEach(function (x) { x.classList.remove('sel'); });
  });
  document.addEventListener('click', function (e) {
    var bar = e.target.closest && e.target.closest('.xbar');
    if (!bar) return;
    if (justDragged) { justDragged = false; return; }
    zoom(bar.dataset.from, bar.dataset.to);
  });
  function zoom(from, to) { load({ since: from, until: to, range: '', dateFrom: '', dateTo: '' }); }

  /* ── review actions from the panel ────────────────────────────────────── */
  function markReview(id, status) {
    htmx.ajax('POST', '/audit/review/' + encodeURIComponent(id), { target: '#eventPanel', swap: 'none', values: { status: status } });
  }
  document.body.addEventListener('auditReviewed', function (evt) {
    var d = evt.detail || {};
    toast((MSG.mMarked || '').replace('{0}', d.id || '').replace('{1}', d.status || ''), 'ok');
    forcePanel = true;
    fetchRows();
    if (d.id) { sel = d.id; }
  });

  /* ── drawer: the full record (unchanged detail view) ──────────────────── */
  function openDrawer(id) {
    $('#drawerOverlay').classList.add('open'); $('#drawerPanel').classList.add('open');
    htmx.ajax('GET', '/audit/detail/' + encodeURIComponent(id), { target: '#drawerPanel', swap: 'innerHTML' });
  }
  function closeDrawer() { $('#drawerOverlay').classList.remove('open'); $('#drawerPanel').classList.remove('open'); }
  window.openDrawer = openDrawer; window.closeDrawer = closeDrawer;

  function toast(msg, type) {
    var c = $('#toastContainer'); if (!c || !msg) return;
    var t = document.createElement('div'); t.className = 'toast ' + (type || 'ok'); t.textContent = msg; c.appendChild(t);
    setTimeout(function () { t.style.opacity = '0'; setTimeout(function () { t.remove(); }, 400); }, 3500);
  }
  window.showToast = toast;

  /* ── verify the hash chain (unchanged) ────────────────────────────────── */
  function verifyChain() {
    var btn = $('#verifyBtn'); if (!btn) return;
    var day = (S.dateFrom && S.dateFrom === S.dateTo) ? S.dateFrom : '';
    btn.disabled = true;
    fetch('/audit/verify-chain' + (day ? '?partition=' + encodeURIComponent(day) : ''), { credentials: 'same-origin' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (j.error) { toast(j.error, 'err'); return; }
        if (Number(j.eventsChecked || 0) === 0) { toast('Nothing to verify: no events in ' + j.partition + '.', 'ok'); return; }
        var ok = j.valid === true && Number(j.problemCount || 0) === 0 && Number(j.unchainedEvents || 0) === 0;
        var vs = $('#verifyStatus');
        if (vs) {
          vs.style.color = ok ? 'var(--ok-fg)' : 'var(--bad-fg)';
          vs.textContent = (ok ? 'Chain valid' : 'CHAIN PROBLEM') + ' · ' + j.partition + ' · ' + new Date().toLocaleTimeString();
          vs.title = (j.details && j.details.length) ? j.details.slice(0, 5).join('\n') : 'No problems found';
        }
        toast((ok ? 'Chain valid' : 'CHAIN PROBLEM') + ' (' + j.partition + '): ' + (j.eventsChecked || 0) + ' events checked, ' + (j.problemCount || 0) + ' problems, ' + (j.unchainedEvents || 0) + ' unchained', ok ? 'ok' : 'err');
      })
      .catch(function () { toast('Could not verify the chain.', 'err'); })
      .then(function () { btn.disabled = false; });
  }

  /* ── keyboard ─────────────────────────────────────────────────────────── */
  document.addEventListener('keydown', function (e) {
    var tag = e.target.tagName;
    if (e.key === 'Escape') { closeMenus(); closeDrawer(); $('#saveForm') && ($('#saveForm').hidden = true); return; }
    if (e.key === 'Enter' && e.target.matches && e.target.matches('#auditTbody tr.ex-row, tr.ex-grow')) { e.target.click(); return; }
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || e.ctrlKey || e.metaKey || e.altKey) return;
    if (document.getElementById('findPalette') && !document.getElementById('findPalette').hidden) return;
    if (e.key === '/') { e.preventDefault(); $('#searchInput').focus(); }
    else if (e.key === 'r' || e.key === 'R') { fetchRows(); }
    else if ((e.key === 'ArrowDown' || e.key === 'ArrowUp' || e.key === 'j' || e.key === 'k') && sel) {
      var rows = $$('#auditTbody tr.ex-row').filter(function (r) { return !r.hidden; }), i = rows.findIndex(function (r) { return r.dataset.id === sel; });
      var n = rows[i + (e.key === 'ArrowDown' || e.key === 'j' ? 1 : -1)];
      if (n) { e.preventDefault(); selectRow(n); n.scrollIntoView({ block: 'nearest' }); }
    }
  });

  /* ── start ────────────────────────────────────────────────────────────── */
  document.addEventListener('DOMContentLoaded', function () {
    readState();
    var panelId = S.open || null;
    if (panelId) { sel = panelId; }
    syncUrl();
    var rows = $$('#auditTbody tr.ex-row:not(.ex-child)');
    var first = (panelId && $('#row-' + cssId(panelId))) || rows[0];
    if (first) selectRow(first, true); else if (panelId) loadPanelFor(panelId);
    delete S.open;
  });
})();
