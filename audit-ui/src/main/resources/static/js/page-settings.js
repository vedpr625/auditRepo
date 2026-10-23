/* Settings: highlights the section in the left list that the person jumped to or is reading. */
(function () {
  'use strict';
  var links = Array.prototype.slice.call(document.querySelectorAll('#setNav a'));
  if (!links.length) return;
  function mark(hash) {
    var h = hash || '#prefs';
    var hit = links.some(function (a) { return a.getAttribute('href') === h; });
    links.forEach(function (a) { a.classList.toggle('on', hit ? a.getAttribute('href') === h : a.getAttribute('href') === '#prefs'); });
  }
  links.forEach(function (a) { a.addEventListener('click', function () { mark(a.getAttribute('href')); }); });
  window.addEventListener('hashchange', function () { mark(location.hash); });
  if (!('IntersectionObserver' in window)) { mark(location.hash); return; }
  var ids = links.map(function (a) { return a.getAttribute('href').slice(1); });
  var seen = {};
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (e) { seen[e.target.id] = e.isIntersecting ? e.boundingClientRect.top : null; });
    var best = null;
    ids.forEach(function (id) { if (seen[id] != null && (best === null || seen[id] < seen[best])) best = id; });
    if (best) mark('#' + best);
  }, { rootMargin: '-60px 0px -60% 0px' });
  ids.forEach(function (id) { var el = document.getElementById(id); if (el) io.observe(el); });
  mark(location.hash);
})();
