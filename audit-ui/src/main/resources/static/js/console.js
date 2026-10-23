/* Shared behaviour for every page: menus, appearance, toasts, request progress. No libraries beyond htmx. */
(function () {
  "use strict";
  var doc = document, root = doc.documentElement;
  var T = (root.lang || "en");

  function meta(n) { var m = doc.querySelector("meta[name=" + n + "]"); return m ? m.content : null; }
  function text(key, fallback) { var el = doc.getElementById("toasts"); return (el && el.dataset[key]) || fallback; }

  // ── CSRF on every htmx request ──
  doc.addEventListener("htmx:configRequest", function (e) {
    var t = meta("_csrf"), h = meta("_csrf_header");
    if (t && h) e.detail.headers[h] = t;
  });

  // ── Toasts ──
  var box;
  function toast(message, kind, ms) {
    box = box || doc.getElementById("toasts");
    if (!box || !message) return;
    var el = doc.createElement("div");
    el.className = "toast toast-" + (kind || "ok");
    el.setAttribute("role", kind === "bad" ? "alert" : "status");
    var span = doc.createElement("span"); span.textContent = message;
    var b = doc.createElement("button"); b.type = "button"; b.className = "toast-x"; b.textContent = "×";
    b.setAttribute("aria-label", "Close");
    b.addEventListener("click", function () { el.remove(); });
    el.appendChild(span); el.appendChild(b);
    box.appendChild(el);
    while (box.children.length > 3) box.removeChild(box.firstChild);
    setTimeout(function () { el.classList.add("out"); setTimeout(function () { el.remove(); }, 250); }, ms || (kind === "bad" ? 7000 : 3800));
  }
  window.AuditToast = toast;

  // Messages the server sent back after a form post (marked data-flash) become toasts. The page text stays for screen readers.
  function flashToToasts() {
    doc.querySelectorAll("[data-flash]").forEach(function (n) {
      var bad = n.classList.contains("notice-bad");
      toast((n.textContent || "").trim(), bad ? "bad" : "ok");
      if (!bad) n.classList.add("sr-only");
    });
  }

  // ── Menus (details elements): one open at a time, close on outside click, Escape and link use ──
  function closeMenus(except) { doc.querySelectorAll("details.dd[open]").forEach(function (d) { if (d !== except) d.removeAttribute("open"); }); }
  doc.addEventListener("click", function (e) {
    var inside = e.target.closest && e.target.closest("details.dd");
    closeMenus(inside);
    var tog = e.target.closest && e.target.closest(".nav-toggle");
    if (tog) { e.preventDefault(); setNav(!root.classList.contains("nav-open")); return; }
    if (e.target.closest && e.target.closest(".main-nav a")) setNav(false);
  });
  doc.addEventListener("keydown", function (e) {
    if (e.key !== "Escape") return;
    var open = doc.querySelector("details.dd[open]");
    if (open) { open.removeAttribute("open"); var s = open.querySelector("summary"); if (s) s.focus(); }
    if (root.classList.contains("nav-open")) { setNav(false); var t = doc.querySelector(".nav-toggle"); if (t) t.focus(); }
  });
  function setNav(on) {
    root.classList.toggle("nav-open", on);
    var t = doc.querySelector(".nav-toggle"); if (t) t.setAttribute("aria-expanded", on ? "true" : "false");
  }

  // ── Appearance: apply at once, save in the background ──
  function applyAppearance(theme, accent) {
    if (theme) {
      if (theme === "system") root.removeAttribute("data-theme"); else root.setAttribute("data-theme", theme);
    }
    if (accent) {
      if (accent === "clinic") root.removeAttribute("data-accent"); else root.setAttribute("data-accent", accent);
    }
    doc.querySelectorAll("[data-set-theme]").forEach(function (b) { if (theme) b.setAttribute("aria-pressed", String(b.dataset.setTheme === theme)); });
    doc.querySelectorAll("[data-set-accent]").forEach(function (b) { if (accent) b.setAttribute("aria-pressed", String(b.dataset.setAccent === accent)); });
    doc.querySelectorAll("input[name=theme]").forEach(function (r) { if (theme) r.checked = r.value === theme; });
    doc.querySelectorAll("input[name=accent]").forEach(function (r) { if (accent) r.checked = r.value === accent; });
    try {
      var cur = JSON.parse(localStorage.getItem("audit.appearance") || "{}");
      if (theme) cur.theme = theme; if (accent) cur.accent = accent;
      localStorage.setItem("audit.appearance", JSON.stringify(cur));
    } catch (e) { /* storage blocked */ }
  }
  doc.addEventListener("click", function (e) {
    var b = e.target.closest && e.target.closest("[data-appearance-form] button[type=submit]");
    if (!b || !window.fetch) return;           // without scripts the form posts and the page reloads
    e.preventDefault();
    var theme = b.dataset.setTheme || null, accent = b.dataset.setAccent || null;
    applyAppearance(theme, accent);
    var body = new URLSearchParams();
    if (theme) body.set("theme", theme);
    if (accent) body.set("accent", accent);
    var h = { "X-Requested-With": "fetch", "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json" };
    var t = meta("_csrf"), hn = meta("_csrf_header"); if (t && hn) h[hn] = t;
    fetch(b.form.action, { method: "POST", headers: h, body: body, credentials: "same-origin" })
      .then(function (r) {
        if (r.ok) toast(text("saved", "Saved"), "ok", 1800);
        else toast(text("failed", "Could not save this choice. It is kept for now on this device only."), "bad");
      })
      .catch(function () { toast(text("failed", "Could not save this choice. It is kept for now on this device only."), "bad"); });
  });
  // Settings page: choosing a swatch or mode previews it straight away (saving still happens with the Save button)
  doc.addEventListener("change", function (e) {
    var r = e.target;
    if (!r || !r.name || !r.closest || !r.closest("[data-appearance-preview]")) return;
    if (r.name === "theme") applyAppearance(r.value, null);
    if (r.name === "accent") applyAppearance(null, r.value);
  });

  // ── Requests in flight: a thin progress bar under the header, errors as toasts ──
  var busy = 0;
  function setBusy(d) { busy = Math.max(0, busy + d); root.toggleAttribute("data-busy", busy > 0); }
  doc.addEventListener("htmx:beforeRequest", function () { setBusy(1); });
  doc.addEventListener("htmx:afterRequest", function (e) {
    setBusy(-1);
    var cfg = e.detail && e.detail.requestConfig, verb = cfg && cfg.verb;
    if (e.detail && e.detail.successful && verb && verb !== "get" && e.detail.elt && e.detail.elt.hasAttribute("data-toast")) {
      toast(e.detail.elt.getAttribute("data-toast"), "ok");
    }
  });
  doc.addEventListener("htmx:responseError", function (e) {
    e.detail.target.setAttribute("data-failed", "true");
    toast(text("failed2", "That did not work. Nothing was changed. Try again."), "bad");
  });
  doc.addEventListener("htmx:sendError", function () { toast(text("offline", "Could not reach the server. Check the connection and try again."), "bad"); });
  doc.addEventListener("htmx:timeout", function () { toast(text("slow", "The server is slow to answer. Try again in a moment."), "bad"); });

  // ── Sticky table headers sit under the sticky page header: tell CSS how tall it is ──
  function headerHeight() {
    var h = doc.getElementById("app-header");
    if (h) root.style.setProperty("--header-h", h.offsetHeight + "px");
  }
  window.addEventListener("resize", headerHeight);

  doc.addEventListener("DOMContentLoaded", function () { headerHeight(); flashToToasts(); });
})();

// Ctrl K (or Cmd K) jumps to "Find anything"
document.addEventListener("keydown", function (e) {
  if ((e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K")) {
    var f = document.getElementById("find-q");
    if (f && f.offsetParent !== null) { e.preventDefault(); f.focus(); f.select(); }
  }
});
