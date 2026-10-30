// Motion kit v1 core (docs/pipeline-d/README.md "The scene kit"), seeded from the Os Lusíadas Ep.00 engine
// (reference/lusiadas-ep00/ep0.html). Time contract: a scene declares its timeline once; seek(t) draws the exact frame
// for time t. No timers, no requestAnimationFrame, no Date, no Math.random: the same t always gives the same frame.
(function () {
  'use strict';

  // ---------- frame ----------
  const FRAMES = {
    // Landscape: YouTube title-safe margins. Vertical: Shorts / TikTok / Reels safe zone (buttons and captions).
    landscape: { W: 1920, H: 1080, safe: { left: 96, top: 54, right: 96, bottom: 54 } },
    vertical: { W: 1080, H: 1920, safe: { left: 60, top: 220, right: 140, bottom: 480 } },
  };
  const params = new URLSearchParams(location.search);

  /** Called once by a scene: K.setup({frame, duration, palette}). The frame can be overridden with ?frame=. */
  function setup(opts) {
    const name = params.get('frame') || opts.frame || 'landscape';
    const f = FRAMES[name];
    if (!f) throw new Error('unknown frame ' + name);
    K.frame = name; K.W = f.W; K.H = f.H; K.landscape = name === 'landscape';
    K.safe = { x: f.safe.left, y: f.safe.top, w: f.W - f.safe.left - f.safe.right, h: f.H - f.safe.top - f.safe.bottom };
    // u: one design unit; 1 at 1080 px on the short side, so sizes read the same in both frames.
    K.u = Math.min(f.W, f.H) / 1080;
    K.duration = opts.duration;
    const root = document.documentElement.style;
    for (const [k, v] of Object.entries(opts.palette || {})) root.setProperty('--' + k, v);
    const wrap = document.getElementById('wrap');
    wrap.style.width = f.W + 'px'; wrap.style.height = f.H + 'px';
    const safe = document.getElementById('safe');
    Object.assign(safe.style, { left: K.safe.x + 'px', top: K.safe.y + 'px', width: K.safe.w + 'px', height: K.safe.h + 'px',
      display: params.get('safe') ? 'block' : 'none' });
    grainCanvas();
  }

  // ---------- easing ----------
  const E = {
    lin: x => x,
    out: x => 1 - Math.pow(1 - x, 3),
    in: x => x * x * x,
    io: x => x < .5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2,
    back: x => { const c = 1.9, c3 = c + 1; return 1 + c3 * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2); },
    el: x => x === 0 || x === 1 ? x : Math.pow(2, -10 * x) * Math.sin((x * 10 - .75) * (2 * Math.PI) / 3) + 1,
  };
  const clamp = (x, a = 0, b = 1) => Math.max(a, Math.min(b, x));
  const prog = (t, a, b) => clamp((t - a) / (b - a));

  /** Seeded random numbers (mulberry32); the only randomness a scene may use. */
  function rng(seed) {
    return () => { seed |= 0; seed = seed + 0x6D2B79F5 | 0; let t = Math.imul(seed ^ seed >>> 15, 1 | seed);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
  }

  // ---------- elements and tweens ----------
  const ALL = [], HOOKS = [], FLASHES = [];
  const DEF = { x: 0, y: 0, s: 1, r: 0, o: 1, b: 0, cl: 1, tc: 1 };
  let OFF = 0; // scene-local time offset

  /** Runs build() with times relative to t0, so a block can be written in local time and placed anywhere. */
  function at(t0, build) { const prev = OFF; OFF += t0; try { build(); } finally { OFF = prev; } }

  /**
   * A positioned element. Tweens animate x, y (px offsets), s (scale), r (deg), o (opacity), b (blur px),
   * cl (left-to-right reveal 0..1) and tc (typed fraction of el._text 0..1).
   */
  function mk(html, style = {}, parent, cls = '') {
    const d = document.createElement('div');
    d.className = 'abs ' + cls; d.innerHTML = html;
    Object.assign(d.style, style);
    (parent ? (parent.el || parent) : document.getElementById('stage')).appendChild(d);
    d._tw = []; d._disp = style.display || ''; ALL.push(d);
    d.tw = (t0, dur, from, to, ease = 'out') => { d._tw.push({ t0: t0 + OFF, t1: t0 + OFF + dur, from, to, ease }); return d; };
    return d;
  }
  function svg(inner, style = {}, parent, vb) {
    return mk(`<svg viewBox="${vb || `0 0 ${K.W} ${K.H}`}" width="100%" height="100%" xmlns="http://www.w3.org/2000/svg">${inner}</svg>`, style, parent);
  }
  function typed(text, style, parent, cls) { const d = mk('', style, parent, cls); d._text = text; return d; }
  /** Text at (x, y) in px with font size in design units. */
  function T(html, x, y, size, cls = 'h', ex = {}, parent) {
    return mk(html, Object.assign({ left: x + 'px', top: y + 'px', fontSize: size * K.u + 'px' }, ex), parent, cls);
  }
  /** Text centred across the safe area. */
  function C(html, y, size, cls = 'h', ex = {}, parent) {
    return T(html, K.safe.x, y, size, cls, Object.assign({ width: K.safe.w + 'px', textAlign: 'center' }, ex), parent);
  }
  /** A full-frame container shown only between s and e, fading in and out. */
  function scene(s, e, bg) {
    const c = mk('', { left: 0, top: 0, width: K.W + 'px', height: K.H + 'px', background: bg || 'transparent' });
    c.tw(s, .45, { o: 0 }, { o: 1 }, 'lin'); c.tw(e - .45, .45, {}, { o: 0 }, 'lin');
    c._s = s + OFF; c._e = e + OFF; c.el = c; return c;
  }
  /** A per-frame hook, for things tweens can't express (paths, counters). Receives local time. */
  function hook(f) { const o = OFF; HOOKS.push(t => f(t - o)); }
  /** A white flash at t (with the sound hit cue the assembler places from K.cues). */
  function flash(t, a = .9, d = .35) { FLASHES.push([t + OFF, a, d]); cue('hit', t); }
  const CUES = [];
  /** Sound cues for the assembler (effects from the licensed library, by id). */
  function cue(id, t) { CUES.push({ id, t: +(t + OFF).toFixed(3) }); }

  const IN = (d, t, dy = 60, dur = .7) => d.tw(t, dur, { o: 0, y: dy * K.u }, { o: 1, y: 0 }, 'out');
  const POP = (d, t, dur = .6) => d.tw(t, dur, { o: 0, s: .4 }, { o: 1, s: 1 }, 'back');
  const OUT = (d, t, dur = .4) => d.tw(t, dur, {}, { o: 0, y: -30 * K.u }, 'in');
  const WIPE = (d, t, dur = .8) => d.tw(t, dur, { cl: 0 }, { cl: 1 }, 'io');

  // ---------- render ----------
  function seek(t) {
    for (const d of ALL) {
      const st = { ...DEF }, touched = {};
      for (const w of d._tw) {
        if (t >= w.t0) {
          const p = w.t1 > w.t0 ? Math.min(1, (t - w.t0) / (w.t1 - w.t0)) : 1, k = E[w.ease](p);
          for (const key in w.to) { const a = (key in w.from) ? w.from[key] : st[key]; st[key] = a + (w.to[key] - a) * k; touched[key] = 1; }
          for (const key in w.from) if (!(key in w.to)) { st[key] = w.from[key]; touched[key] = 1; }
        } else {
          for (const key in w.from) if (!touched[key]) { st[key] = w.from[key]; touched[key] = 1; }
        }
      }
      if (d._s !== undefined && (t < d._s - .01 || t > d._e + .01)) { d.style.display = 'none'; continue; }
      if (st.o <= 0.002) { d.style.display = 'none'; continue; }
      d.style.display = d._disp;
      d.style.opacity = st.o;
      d.style.transform = `translate(${st.x}px,${st.y}px) rotate(${st.r}deg) scale(${st.s})`;
      d.style.filter = st.b > 0.05 ? `blur(${st.b}px)` : '';
      d.style.clipPath = st.cl < 0.999 ? `inset(-10% ${(1 - st.cl) * 110}% -10% -5%)` : '';
      if (d._text !== undefined) d.textContent = d._text.slice(0, Math.round(d._text.length * st.tc));
    }
    for (const h of HOOKS) h(t);
    let o = 0;
    for (const [f, a, d] of FLASHES) if (t >= f && t < f + d) o = Math.max(o, a * (1 - (t - f) / d));
    document.getElementById('flash').style.opacity = o;
    const g = document.getElementById('grain'), r = Math.floor(t * 24) * 7919;
    g.style.transform = `translate(${(r % 173) - 86}px,${(r % 131) - 65}px) scale(4)`;
  }

  function grainCanvas() {
    const g = document.getElementById('grain'), c = g.getContext('2d'), im = c.createImageData(512, 512);
    const rnd = rng(12345);
    for (let i = 0; i < im.data.length; i += 4) { const v = rnd() * 255; im.data[i] = im.data[i + 1] = im.data[i + 2] = v; im.data[i + 3] = 255; }
    c.putImageData(im, 0, 0);
  }

  /** Call after building: waits for the bundled fonts, then exposes the contract the renderer reads. */
  function ready() {
    const fonts = ['900 40px Inter', '800 40px Inter', '700 40px "JetBrains Mono"', '500 40px "JetBrains Mono"',
      'italic 500 40px "Playfair Display"', 'italic 700 40px "Playfair Display"'];
    return Promise.all(fonts.map(f => document.fonts.load(f))).then(() => {
      window.DURATION = K.duration; window.FRAME = { name: K.frame, width: K.W, height: K.H };
      window.CUES = CUES.slice().sort((a, b) => a.t - b.t);
      window.seek = seek;
      seek(parseFloat(params.get('t') || '0'));
      window.READY = true;
    });
  }

  const K = { setup, E, clamp, prog, rng, at, mk, svg, typed, T, C, scene, hook, flash, cue, IN, POP, OUT, WIPE, seek, ready,
    version: '1.0.0' };
  window.K = K;
})();
