// A cookie banner that was hidden may have locked scrolling (overflow:hidden on html or body). Only when
// one of the cookie rules really matches an element of this page is the lock removed.
(function () {
  function unlock() {
    var sheet = document.getElementById('k0t1k-cookies');
    sheet = sheet && sheet.sheet;
    if (!sheet) return;
    var hit = false;
    for (var i = 0; i < sheet.cssRules.length && !hit; i++) {
      try { hit = !!document.querySelector(sheet.cssRules[i].selectorText); } catch (e) {}
    }
    if (!hit) return;
    [document.documentElement, document.body].forEach(function (el) {
      if (el && /hidden/.test(getComputedStyle(el).overflow + getComputedStyle(el).overflowY)) {
        el.style.setProperty('overflow', 'auto', 'important');
      }
    });
  }
  [1000, 3000, 6000].forEach(function (ms) { setTimeout(unlock, ms); });
})();
