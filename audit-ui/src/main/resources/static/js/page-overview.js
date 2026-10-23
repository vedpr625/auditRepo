/* Overview: choosing a facility applies it at once (no Apply button needed when scripts run). */
document.addEventListener("change", function (e) {
  var t = e.target;
  if (t && t.hasAttribute && t.hasAttribute("data-autosubmit") && t.form) t.form.submit();
});
