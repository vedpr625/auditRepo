/* Ctrl K Find palette. Backed by GET /find/suggest?q=&scope=. Without scripts the header Find box still posts to the /find page. */
(function () {
  'use strict';
  var root, input, list, scopeTag, narrow, lastFocus, timer, ctl, items = [], active = -1, scope = 'all', seq = 0;
  var SCOPES = ['patient', 'person', 'facility', 'case', 'event', 'visit'];

  function $(s, r) { return (r || document).querySelector(s); }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }

  function init() {
    root = document.getElementById('findPalette');
    if (!root) return false;
    input = $('#fpInput', root); list = $('#fpList', root); scopeTag = $('#fpScope', root); narrow = $('.fp-narrow', root);

    input.addEventListener('input', function () { readPrefix(); schedule(); });
    input.addEventListener('keydown', onKey);
    root.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { e.preventDefault(); close(); }
      else if (e.key === 'Tab') trap(e);
    });
    root.addEventListener('mousedown', function (e) { if (e.target === root) close(); });
    narrow.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-scope]'); if (!b) return;
      setScope(scope === b.dataset.scope ? 'all' : b.dataset.scope); input.focus(); run();
    });
    list.addEventListener('mousemove', function (e) { var a = e.target.closest('.fp-item'); if (a) setActive(items.indexOf(a), false); });
    list.addEventListener('click', function (e) {
      var a = e.target.closest('.fp-item'); if (!a) return;
      e.preventDefault(); go(a, e.ctrlKey || e.metaKey);
    });
    return true;
  }

  function open(preset) {
    if (!root && !init()) return false;
    lastFocus = document.activeElement;
    root.hidden = false;
    document.body.style.overflow = 'hidden';
    input.value = typeof preset === 'string' ? preset : '';
    setScope('all');
    input.focus(); input.select();
    run();
    return true;
  }
  function close() {
    if (!root || root.hidden) return;
    root.hidden = true; document.body.style.overflow = '';
    if (ctl) ctl.abort();
    if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (e) { /* element gone */ } }
  }

  function setScope(s) {
    scope = SCOPES.indexOf(s) >= 0 ? s : 'all';
    scopeTag.hidden = scope === 'all'; scopeTag.textContent = scope === 'all' ? '' : '/' + scope;
    Array.prototype.forEach.call(narrow.querySelectorAll('button[data-scope]'), function (b) { b.setAttribute('aria-pressed', b.dataset.scope === scope ? 'true' : 'false'); });
  }
  /* typing "/patient " or "/visit E-8" at the start narrows the search, like the chips do */
  function readPrefix() {
    var m = /^\/([a-z]+)\s+/i.exec(input.value);
    if (m && SCOPES.indexOf(m[1].toLowerCase()) >= 0) { setScope(m[1].toLowerCase()); input.value = input.value.slice(m[0].length); }
  }

  function schedule() { clearTimeout(timer); timer = setTimeout(run, 140); }

  function run() {
    var q = input.value.trim(), my = ++seq;
    if (ctl) ctl.abort();
    if (q.length < 2) { render({ groups: [], hint: 'short' }); return; }
    ctl = ('AbortController' in window) ? new AbortController() : null;
    list.setAttribute('aria-busy', 'true');
    fetch('/find/suggest?q=' + encodeURIComponent(q) + '&scope=' + encodeURIComponent(scope), { credentials: 'same-origin', headers: { 'Accept': 'application/json' }, signal: ctl ? ctl.signal : undefined })
      .then(function (r) { if (!r.ok) throw new Error('http'); return r.json(); })
      .then(function (j) { if (my === seq) render(j); })
      .catch(function (e) { if (e && e.name === 'AbortError') return; if (my === seq) render({ groups: [], error: 'x' }); });
  }

  function render(j) {
    list.textContent = ''; items = []; active = -1; list.removeAttribute('aria-busy');
    if (j.hint) { list.appendChild(el('div', 'fp-msg', root.dataset['hint' + j.hint.charAt(0).toUpperCase() + j.hint.slice(1)] || '')); return; }
    if (j.error) list.appendChild(el('div', 'fp-msg bad', root.dataset.error));
    (j.groups || []).forEach(function (g) {
      list.appendChild(el('div', 'fp-group', g.title));
      (g.items || []).forEach(function (it) {
        var a = el('a', 'fp-item'); a.href = it.open; a.setAttribute('role', 'option'); a.id = 'fp-opt-' + items.length;
        a.className += ' k-' + it.kind; a.dataset.open = it.open; a.dataset.search = it.search || it.open;
        a.appendChild(el('span', 'fp-badge', it.badge));
        var main = el('span', 'fp-main');
        main.appendChild(el('span', 'fp-title' + (it.kind === 'patient' ? ' mono' : ''), it.title));
        if (it.sub) main.appendChild(el('span', 'fp-sub', it.sub));
        a.appendChild(main);
        if (it.meta) a.appendChild(el('span', 'fp-meta', it.meta));
        list.appendChild(a); items.push(a);
      });
    });
    if (!items.length && !j.error) list.appendChild(el('div', 'fp-msg', root.dataset.empty));
    if (items.length) setActive(0, true);
  }

  function setActive(i, scroll) {
    if (i < 0 || i >= items.length) return;
    if (active >= 0 && items[active]) { items[active].classList.remove('on'); items[active].removeAttribute('aria-selected'); }
    active = i; items[i].classList.add('on'); items[i].setAttribute('aria-selected', 'true');
    input.setAttribute('aria-activedescendant', items[i].id);
    if (scroll) items[i].scrollIntoView({ block: 'nearest' });
  }

  function go(a, searchEvents) { location.href = searchEvents ? a.dataset.search : a.dataset.open; }

  function onKey(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); if (items.length) setActive((active + 1) % items.length, true); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); if (items.length) setActive((active - 1 + items.length) % items.length, true); }
    else if (e.key === 'Enter') {
      e.preventDefault();
      if (active >= 0 && items[active]) go(items[active], e.ctrlKey || e.metaKey);
    } else if (e.key === 'Backspace' && input.value === '' && scope !== 'all') { setScope('all'); run(); }
  }

  function trap(e) {
    var f = Array.prototype.slice.call(root.querySelectorAll('input,button')).filter(function (x) { return !x.disabled && x.offsetParent !== null; });
    if (!f.length) return;
    var first = f[0], last = f[f.length - 1];
    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
  }

  /* Ctrl K / Cmd K: capture phase on window so the older "focus the header box" handler never runs */
  window.addEventListener('keydown', function (e) {
    if ((e.ctrlKey || e.metaKey) && !e.shiftKey && !e.altKey && (e.key === 'k' || e.key === 'K')) {
      if (!root && !init()) return;
      e.preventDefault(); e.stopImmediatePropagation();
      if (root.hidden) open(); else close();
    }
  }, true);

  /* the header Find box opens the palette (without scripts it still submits to /find) */
  document.addEventListener('focusin', function (e) {
    if (e.target && e.target.id === 'find-q') { var typed = e.target.value; e.target.blur(); open(typed); }
  });
  document.addEventListener('click', function (e) {
    if (e.target && e.target.id === 'find-q') { e.preventDefault(); open(e.target.value); }
  });
  window.openFindPalette = open;
})();
