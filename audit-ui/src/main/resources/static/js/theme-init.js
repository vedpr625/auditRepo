/* Runs before the page is painted (no defer), so the chosen colours are there from the first frame.
   A signed-in person's saved choice comes from the server (data-* on this script tag). On the sign-in page
   nobody is known yet, so the last choice made on this device is used instead. */
(function () {
  var s = document.currentScript, d = document.documentElement, ds = (s && s.dataset) || {};
  var theme = ds.theme || "", accent = ds.accent || "";
  if (ds.known !== "1") {
    try {
      var saved = JSON.parse(localStorage.getItem("audit.appearance") || "{}");
      theme = saved.theme && saved.theme !== "system" ? saved.theme : "";
      accent = saved.accent && saved.accent !== "clinic" ? saved.accent : "";
    } catch (e) { /* storage blocked: follow the system */ }
  }
  if (theme && !d.getAttribute("data-theme")) d.setAttribute("data-theme", theme);
  if (accent && !d.getAttribute("data-accent")) d.setAttribute("data-accent", accent);
  d.classList.add("js");
})();
