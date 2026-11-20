// Retrieve website: hero demo typing loop and scroll reveals. No dependencies.
(function () {
  var reduce = window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  // Typing demo in the phone
  var typed = document.getElementById('typed');
  var results = document.getElementById('results');
  var q = 'raft log matching';
  if (reduce) { typed.textContent = q; results.classList.remove('hide'); }
  else {
    var i = 0, phase = 'type';
    (function tick() {
      if (phase === 'type') {
        typed.textContent = q.slice(0, ++i);
        if (i >= q.length) { phase = 'show'; setTimeout(tick, 350); } else setTimeout(tick, 70 + Math.random() * 60);
      } else if (phase === 'show') {
        results.classList.remove('hide'); phase = 'hold'; setTimeout(tick, 3800);
      } else {
        results.classList.add('hide'); typed.textContent = ''; i = 0; phase = 'type'; setTimeout(tick, 900);
      }
    })();
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
