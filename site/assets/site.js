// Hero: one orchestrated moment. A spoken sentence becomes a tool call, then a logged card,
// and the day's remaining calories update. Static final state without JS or with reduced motion.
(() => {
  document.documentElement.classList.add("js");
  const ledger = document.querySelector(".ledger");
  if (!ledger) return;
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const num = ledger.querySelector(".ledger-num .n");
  const bar = ledger.querySelector(".bar");
  const quote = ledger.querySelector("q.typed");
  const full = quote.textContent;
  const steps = Array.from(ledger.querySelectorAll(".script > li"));
  steps.forEach((s) => { s.dataset.step = ""; });
  const TOTAL = 2400, FROM = 1880, TO = 1468;
  const fmt = (n) => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, " ");
  let timers = [];
  const later = (fn, ms) => timers.push(setTimeout(fn, ms));
  const setLeft = (left) => { num.textContent = fmt(left); };
  const setBar = (left) => bar.style.setProperty("--fill", ((TOTAL - left) / TOTAL) * 100 + "%");

  function finalState() {
    steps.forEach((s) => s.classList.add("on"));
    quote.textContent = full;
    quote.classList.remove("typing");
    setLeft(TO); setBar(TO);
  }
  function countDown() {
    setBar(TO);
    const t0 = performance.now(), d = 900;
    const frame = (t) => {
      const p = Math.max(0, Math.min(1, (t - t0) / d)), e = 1 - Math.pow(1 - p, 3);
      setLeft(Math.round(FROM + (TO - FROM) * e));
      if (p < 1) requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
  }
  function play() {
    timers.forEach(clearTimeout); timers = [];
    if (reduce) { finalState(); return; }
    steps.forEach((s) => s.classList.remove("on"));
    setLeft(FROM); setBar(FROM);
    quote.textContent = "";
    later(() => {
      steps[0].classList.add("on");
      quote.classList.add("typing");
      let i = 0;
      const type = () => {
        quote.textContent = full.slice(0, ++i);
        if (i < full.length) { later(type, 26 + (i % 7) * 6); return; }
        quote.classList.remove("typing");
        later(() => steps[1].classList.add("on"), 450);
        later(() => { steps[2].classList.add("on"); countDown(); }, 1300);
      };
      type();
    }, 600);
  }
  ledger.querySelector(".replay").addEventListener("click", play);
  play();
})();
