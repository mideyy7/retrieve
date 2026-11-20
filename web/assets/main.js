// Retrieve website: hero demo video and scroll reveals. No dependencies.
(function () {
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Hero video: play only the version that matches the layout (wide on desktop, 4:5 on phones),
  // so phones never download the wide file. Reduced-motion users get the poster and a play button.
  var wide = document.querySelector('.hero-video .v-wide');
  var tall = document.querySelector('.hero-video .v-tall');
  var btn = document.querySelector('.hero-video .vid-toggle');
  var narrow = window.matchMedia ? matchMedia('(max-width: 700px)') : { matches: false };
  var userPaused = reduce;

  function current() { return narrow.matches ? tall : wide; }
  function other() { return narrow.matches ? wide : tall; }
  function setButton(playing) {
    if (!btn) return;
    btn.hidden = false;
    btn.setAttribute('aria-label', playing ? 'Pause the demo video' : 'Play the demo video');
    btn.firstElementChild.className = playing ? 'i-pause' : 'i-play';
  }
  function sync() {
    if (!wide || !tall) return;
    other().pause();
    var v = current();
    if (userPaused) { v.pause(); setButton(false); return; }
    v.preload = 'auto';
    var p = v.play();
    if (p && p.catch) p.catch(function () { setButton(false); });
    setButton(true);
  }
  if (reduce) setButton(false);
  if (btn) btn.addEventListener('click', function () {
    var v = current();
    userPaused = !v.paused;
    if (userPaused) { v.pause(); setButton(false); } else { userPaused = false; sync(); }
  });
  if (narrow.addEventListener) narrow.addEventListener('change', sync);
  // Pause when the hero scrolls out of view, resume when it comes back.
  if ('IntersectionObserver' in window && wide) {
    new IntersectionObserver(function (es) {
      es.forEach(function (e) {
        if (userPaused) return;
        if (e.isIntersecting) sync(); else current().pause();
      });
    }, { threshold: 0.15 }).observe(document.querySelector('.hero-video'));
  } else {
    sync();
  }

  // Scroll reveal, only for elements that start below the first screen
  if (!reduce && 'IntersectionObserver' in window) {
    var els = [].slice.call(document.querySelectorAll('.rv'));
    var io = new IntersectionObserver(function (es) {
      es.forEach(function (e) { if (e.isIntersecting) { e.target.classList.remove('pre'); io.unobserve(e.target); } });
    }, { rootMargin: '0px 0px -8% 0px' });
    els.forEach(function (el) {
      if (el.getBoundingClientRect().top > window.innerHeight) { el.classList.add('pre'); io.observe(el); }
    });
  }
})();
