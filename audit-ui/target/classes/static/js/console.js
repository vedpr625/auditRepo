/* Small helpers for the new pages (no framework). */
(function () {
  // htmx: send the CSRF token on every request it makes
  document.addEventListener("htmx:configRequest", function (e) {
    var t = document.querySelector("meta[name=_csrf]"), h = document.querySelector("meta[name=_csrf_header]");
    if (t && h) e.detail.headers[h.content] = t.content;
  });
  // htmx: when Chester or the app does not answer, show a message instead of leaving a blank box
  document.addEventListener("htmx:responseError", function (e) { e.detail.target.setAttribute("data-failed", "true"); });
})();
