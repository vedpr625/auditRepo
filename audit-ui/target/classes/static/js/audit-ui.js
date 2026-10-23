/**
 * audit-ui.js — minimal client-side behaviour for the Audit Review page
 *
 * HTMX does all the heavy lifting (AJAX, fragment swaps).
 * This file handles:
 *   - The toast notification queue
 *   - HTMX event listeners (auditReviewed, htmx:afterSwap errors)
 *   - Keyboard shortcuts
 */

/* ── Toast queue ─────────────────────────────────────────────────────────── */
const _toastQueue = [];
let   _toastRunning = false;

/**
 * showToast(msg, type?)
 * type: 'ok' | 'err' | 'warn'  (adds CSS class to .toast element)
 *
 * Queues toasts so they display one after another rather than stacking
 * on the last-one-wins pattern.
 */
function showToast(msg, type) {
  _toastQueue.push({ msg, type });
  if (!_toastRunning) _drainToastQueue();
}

function _drainToastQueue() {
  if (!_toastQueue.length) { _toastRunning = false; return; }
  _toastRunning = true;

  const { msg, type } = _toastQueue.shift();
  const wrap = document.getElementById('toastWrap');
  if (!wrap) { _drainToastQueue(); return; }

  const el = document.createElement('div');
  el.className = 'toast' + (type ? ' ' + type : '');
  el.setAttribute('role', 'status');
  el.setAttribute('aria-live', 'polite');
  el.textContent = msg;
  wrap.appendChild(el);

  // Force reflow then add .show
  requestAnimationFrame(() => {
    requestAnimationFrame(() => el.classList.add('show'));
  });

  setTimeout(() => {
    el.classList.remove('show');
    setTimeout(() => {
      el.remove();
      _drainToastQueue();
    }, 300);
  }, 2800);
}

/* ── HTMX event: auditReviewed ──────────────────────────────────────────── */
// Fired by the HX-Trigger response header from POST /audit/review/{id}
document.addEventListener('auditReviewed', function(e) {
  const { id, status } = e.detail || {};
  if (id) {
    showToast('AuditEvent/' + id + ' marked as ' + (status || 'reviewed'), 'ok');

    // Dim the row immediately; the HTMX polling or next page load will update it
    const row = document.getElementById('row-' + id);
    if (row) {
      row.style.opacity = '.6';
      row.style.transition = 'opacity .3s';
    }
  }
});

/* ── HTMX error handling ─────────────────────────────────────────────────── */
document.addEventListener('htmx:responseError', function(e) {
  const status = e.detail.xhr && e.detail.xhr.status;
  showToast('Server error ' + (status || '') + ' — please try again', 'err');
});

document.addEventListener('htmx:timeout', function() {
  showToast('Request timed out — is Chester running?', 'warn');
});

document.addEventListener('htmx:sendError', function() {
  showToast('Connection error — cannot reach audit-ui server', 'err');
});

/* ── Keyboard shortcuts ──────────────────────────────────────────────────── */
document.addEventListener('keydown', function(e) {
  // Escape → close drawer
  if (e.key === 'Escape' && typeof closeDrawer === 'function') {
    closeDrawer();
  }

  // Ctrl+/ or Cmd+/ → focus search
  if ((e.ctrlKey || e.metaKey) && e.key === '/') {
    e.preventDefault();
    const search = document.querySelector('[name="q"]');
    if (search) search.focus();
  }
});

/* ── HTMX loading indicator ─────────────────────────────────────────────── */
document.addEventListener('htmx:beforeRequest', function(e) {
  const indicator = document.getElementById('loadSpinner');
  if (indicator) indicator.style.display = 'inline-block';
});

document.addEventListener('htmx:afterRequest', function(e) {
  const indicator = document.getElementById('loadSpinner');
  if (indicator) indicator.style.display = 'none';
});

/* ── Tab switching for page tabs ─────────────────────────────────────────── */
document.addEventListener('DOMContentLoaded', function() {
  const tabs = document.querySelectorAll('.page-tabs .tab-item');
  tabs.forEach(function(tab) {
    tab.addEventListener('click', function() {
      tabs.forEach(t => t.classList.remove('active'));
      this.classList.add('active');
    });
  });
});
