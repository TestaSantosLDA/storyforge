// Motion kit v1 components for the Os Lusíadas series (docs/series/lusiadas.md "Kit components"), ported from Ep.00.
// Each component takes the scene container, its times in seconds, and lays itself out from K.safe, so the same call
// works in landscape (16:9) and vertical (9:16). Sizes are design units (K.u px each).
(function () {
  'use strict';
  const { mk, svg, typed, T, C, hook, IN, POP, OUT, WIPE } = K;
  const L = () => K.landscape;
  const px = v => v + 'px';

  // ---------- backdrops ----------
  /** Gradient backdrop with slow diagonal stripes (Ep.00 title look). */
  function stripes(s, t0, t1, colour = 'rgba(255,200,61,.05)') {
    const n = Math.ceil((K.W + K.H) / 190) + 4;
    const w = svg(Array.from({ length: n }, (_, i) =>
      `<rect x="${-K.H + i * 190}" y="${-K.H}" width="60" height="${K.H * 3}" fill="${colour}" transform="rotate(20 ${K.W / 2} ${K.H / 2})"/>`).join(''),
      { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    hook(t => { if (t >= t0 && t <= t1) w.style.transform = `translateX(${(t - t0) * 30}px)`; });
    return w;
  }

  /** Azulejo tile backdrop: blue-and-white Portuguese tiles drawn in SVG, drifting slowly. */
  function azulejo(s, t0, t1, opacity = .14) {
    const tile = 180 * K.u;
    const pat = `<defs><pattern id="az" width="${tile}" height="${tile}" patternUnits="userSpaceOnUse">
      <rect width="${tile}" height="${tile}" fill="#f6efe2"/>
      <g fill="none" stroke="#1f5fbf" stroke-width="${5 * K.u}">
        <circle cx="${tile / 2}" cy="${tile / 2}" r="${tile * .22}"/>
        <path d="M0 0 Q${tile / 2} ${tile * .3} ${tile} 0 M0 ${tile} Q${tile / 2} ${tile * .7} ${tile} ${tile}
                 M0 0 Q${tile * .3} ${tile / 2} 0 ${tile} M${tile} 0 Q${tile * .7} ${tile / 2} ${tile} ${tile}"/>
      </g><circle cx="${tile / 2}" cy="${tile / 2}" r="${tile * .07}" fill="#1f5fbf"/></pattern></defs>
      <rect class="az-fill" x="${-tile}" y="${-tile}" width="${K.W + 2 * tile}" height="${K.H + 2 * tile}" fill="url(#az)"/>`;
    // Opacity sits on the inner svg: the element's own opacity belongs to its tweens.
    const w = svg(pat, { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    w.firstChild.style.opacity = opacity;
    const fill = w.querySelector('.az-fill');
    hook(t => { if (t >= t0 && t <= t1) fill.setAttribute('transform', `translate(${(-((t - t0) * 12) % tile).toFixed(2)} ${(-((t - t0) * 6) % tile).toFixed(2)})`); });
    return w;
  }

  /** Armillary sphere (Manueline emblem): rings turning slowly, as a backdrop motif. */
  function armillary(s, t0, t1, o = {}) {
    const r = (o.r ?? (L() ? 380 : 420)) * K.u, cx = o.x ?? (L() ? K.W * .72 : K.W / 2), cy = o.y ?? (L() ? K.H / 2 : K.H * .42);
    const rings = [0, 1, 2, 3].map(i => `<ellipse class="ring" cx="0" cy="0" rx="${r}" ry="${r}" fill="none" stroke="var(--gold)" stroke-width="${(i ? 5 : 8) * K.u}"/>`).join('');
    const w = svg(`<g transform="translate(${cx} ${cy})" opacity="${o.opacity ?? .18}">${rings}
      <line x1="${-r * 1.15}" y1="0" x2="${r * 1.15}" y2="0" stroke="var(--gold)" stroke-width="${6 * K.u}" transform="rotate(-23.5)"/>
      <circle r="${r * .12}" fill="var(--gold)"/></g>`, { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    const els = w.querySelectorAll('.ring');
    hook(t => {
      if (t < t0 || t > t1) return;
      const a = (t - t0) * .35;
      els.forEach((e, i) => {
        if (i === 0) return;
        const k = Math.abs(Math.cos(a + i * Math.PI / 3));
        e.setAttribute('ry', (r * Math.max(.04, k)).toFixed(1));
        e.setAttribute('transform', `rotate(${(i - 1) * 60 - 23.5})`);
      });
    });
    return w;
  }

  /** Animated sea: three layered wave paths across the bottom. */
  function waves(s, t0, t1, colour = '#0f3b6b', base, amp = 24, speed = 1.2) {
    base = base ?? K.H * .74;
    const ps = [0, 1, 2].map(i => `<path fill="${colour}" opacity="${.55 + i * .2}"/>`).join('');
    const w = svg(ps, { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    const paths = w.querySelectorAll('path');
    hook(t => {
      if (t < t0 || t > t1) return;
      paths.forEach((p, i) => {
        let d = `M0 ${K.H} `; const yb = base + i * 55 * K.u;
        for (let x = 0; x <= K.W; x += 40) {
          const y = yb + Math.sin(x / 170 + t * speed * (1.2 + i * .35) + i) * amp * K.u + Math.sin(x / 63 - t * speed * 2.1 + i * 2) * amp * .35 * K.u;
          d += `L${x} ${y.toFixed(1)} `;
        }
        p.setAttribute('d', d + `L${K.W} ${K.H} Z`);
      });
    });
    return w;
  }

  /** Wind-driven rain streaks (Ep.00 cold open). */
  function rain(s, t0, t1, n = 90) {
    const w = svg(Array.from({ length: n }, () => '<line stroke="rgba(160,200,255,.35)" stroke-width="2"/>').join(''),
      { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    const ls = w.querySelectorAll('line'), seeds = [...ls].map((_, i) => [(i * 977) % K.W, (i * 613) % K.H, 700 + (i * 37) % 500]);
    hook(t => {
      if (t < t0 || t > t1) return;
      ls.forEach((l, i) => {
        const [x0, y0, v] = seeds[i]; const y = (y0 + t * v) % (K.H + 120) - 60; let x = (x0 - t * v * .25) % K.W; if (x < 0) x += K.W;
        l.setAttribute('x1', x); l.setAttribute('y1', y); l.setAttribute('x2', x - 14); l.setAttribute('y2', y + 55);
      });
    });
    return w;
  }

  // ---------- text furniture ----------
  /** Chapter tag in the top-left of the safe area: "CH.2 · THE CAPE". */
  function chapterTag(s, t, n, title, colour = 'var(--cyan)') {
    const d = T(`CH.${n} · ${title}`, K.safe.x, K.safe.y + 10 * K.u, 26, 'pill', { color: colour, padding: `${10 * K.u}px ${22 * K.u}px`, fontSize: px(24 * K.u) }, s);
    POP(d, t); return d;
  }

  /** Big stat card with a count-up number: statCard(s, t, {value: 170, unit: 'men', label: 'set sail', from: 0}). */
  function statCard(s, t, o) {
    const w = (L() ? 720 : 820) * K.u, x = o.x ?? (K.safe.x + (K.safe.w - w) / 2), y = o.y ?? (K.safe.y + K.safe.h * (L() ? .24 : .36));
    const c = mk('', { left: px(x), top: px(y), width: px(w), padding: px(36 * K.u), textAlign: 'center' }, s, 'card');
    const num = mk('', { position: 'relative', fontSize: px((L() ? 190 : 150) * K.u), color: o.colour || 'var(--gold)' }, c, 'h');
    mk(o.unit || '', { position: 'relative', fontSize: px(46 * K.u), marginTop: px(8 * K.u) }, c, 'h');
    mk(o.label || '', { position: 'relative', fontSize: px(30 * K.u), marginTop: px(14 * K.u), color: 'rgba(246,239,226,.8)' }, c, 'b');
    POP(c, t);
    const from = o.from ?? 0, dur = o.dur ?? 1.4, fmt = o.format || (v => Math.round(v).toLocaleString('en-US'));
    hook(lt => { const p = K.E.out(K.clamp((lt - t - .2) / dur)); num.textContent = (o.prefix || '') + fmt(from + (o.value - from) * p); });
    return c;
  }

  /**
   * Character card for introducing a figure: name, years, role and 2-3 stats with bars (value 0..10).
   * characterCard(s, t, {name: 'Vasco da Gama', years: 'c. 1469–1524', role: 'Captain-major', stats: [['Nerve', 9], ['Patience', 3]]})
   */
  function characterCard(s, t, o) {
    const w = (L() ? 640 : 860) * K.u;
    const x = o.x ?? (L() ? K.safe.x + K.safe.w - w - 40 * K.u : K.safe.x + (K.safe.w - w) / 2);
    const y = o.y ?? (L() ? K.safe.y + K.safe.h * .2 : K.safe.y + K.safe.h * .42);
    const c = mk('', { left: px(x), top: px(y), width: px(w), padding: px(36 * K.u) }, s, 'card');
    mk(o.name, { position: 'relative', fontSize: px(64 * K.u), color: o.colour || 'var(--gold)' }, c, 'h');
    mk(`${o.years} · ${o.role}`, { position: 'relative', fontSize: px(22 * K.u), marginTop: px(14 * K.u), color: 'var(--cyan)', letterSpacing: '.1em' }, c, 'mono');
    c.tw(t, .5, { o: 0, x: 80 * K.u }, { o: 1, x: 0 }, 'out');
    (o.stats || []).forEach(([label, v], i) => {
      const row = mk('', { position: 'relative', marginTop: px(26 * K.u) }, c);
      mk(label, { position: 'relative', fontSize: px(26 * K.u) }, row, 'b');
      const track = mk('', { position: 'relative', marginTop: px(8 * K.u), height: px(16 * K.u), borderRadius: px(8 * K.u), background: 'rgba(255,255,255,.12)' }, row);
      const bar = mk('', { left: 0, top: 0, height: '100%', width: (v * 10) + '%', borderRadius: px(8 * K.u), background: o.colour || 'var(--gold)' }, track);
      bar.tw(t + .5 + i * .25, .7, { cl: 0 }, { cl: 1 }, 'out');
    });
    return c;
  }

  /**
   * Verse Moment: Portuguese stanza lines typed on, then the English, with the citation and translator credit.
   * verse(s, t, {pt: [...], en: [...], ref: 'Canto I · st. 1', translator: 'trans. R. F. Burton, 1880'})
   */
  function verse(s, t, o) {
    const sz = L() ? 46 : 50, gap = sz * 1.45 * K.u;
    const x = K.safe.x + (L() ? 80 * K.u : 20 * K.u), w = K.safe.w - (L() ? 160 : 40) * K.u;
    const top = K.safe.y + (L() ? 60 * K.u : K.safe.h * .12);
    const box = mk('', { left: px(x - 40 * K.u), top: px(top), width: px(w + 80 * K.u), borderLeft: `${8 * K.u}px solid var(--gold)` }, s);
    box.tw(t, .5, { o: 0, cl: 0 }, { o: 1, cl: 1 }, 'io');
    const ref = T(o.ref, x, top + 30 * K.u, 26, 'mono', { color: 'var(--gold)' }, s); IN(ref, t + .2);
    let y = top + 100 * K.u, tt = t + .5;
    for (const line of o.pt) {
      const d = typed(line, { left: px(x), top: px(y), width: px(w), fontSize: px(sz * K.u) }, s, 'serif');
      d.tw(tt, Math.max(.6, line.length / 30), { tc: 0 }, { tc: 1 }, 'lin'); tt += Math.max(.6, line.length / 30) + .15; y += gap;
    }
    y += gap * .5;
    for (const line of o.en) {
      const d = T(line, x, y, sz * .78, 'b', { width: px(w), color: 'var(--cyan)' }, s); IN(d, tt, 30, .5); tt += .35; y += gap * .8;
    }
    const cr = T(o.translator || '', x, y + gap * .3, 22, 'mono', { color: 'rgba(246,239,226,.55)', letterSpacing: '.1em', width: px(w) }, s); IN(cr, tt + .2, 20);
    box.style.height = px(y + gap * .3 + 70 * K.u - top);
    return { end: tt + .4 };
  }

  const STAMPS = { legend: ['LEGEND', 'var(--violet)'], documented: ['DOCUMENTED', 'var(--cyan)'], tradition: ['TRADITION', 'var(--gold)'],
    poem: ['THE POEM SAYS', 'var(--coral)'] };
  /**
   * Claim stamp slammed on screen (history vs legend). kind: legend | documented | tradition | poem.
   * Sits top-right of the safe area (below the chapter tag in vertical), or centred at y with {center: true, y}.
   */
  function stamp(s, t, kind, o = {}) {
    const [label, colour] = STAMPS[kind];
    const y = o.y ?? (K.safe.y + (L() ? 40 : 110) * K.u);
    const row = mk('', { left: px(K.safe.x), top: px(y), width: px(K.safe.w), textAlign: o.center ? 'center' : 'right' }, s);
    const d = mk(label, { position: 'relative', display: 'inline-block', color: colour, padding: `${14 * K.u}px ${30 * K.u}px`, fontSize: px(44 * K.u),
      border: `${6 * K.u}px solid ${colour}`, borderRadius: px(14 * K.u) }, row, 'pill');
    d.tw(t, .35, { o: 0, s: 2.4, r: -18 }, { o: 1, s: 1, r: -8 }, 'out'); K.cue('stamp', t + .3);
    return d;
  }

  /** Fact or Legend? quiz: question, then the stamp answer and the explanation. */
  function factOrLegend(s, t, o) {
    const q = C('FACT OR LEGEND?', K.safe.y + K.safe.h * .08, L() ? 96 : 92, 'h', { color: 'var(--gold)' }, s); POP(q, t);
    const claim = C(o.claim, K.safe.y + K.safe.h * (L() ? .3 : .28), L() ? 56 : 60, 'b', {}, s); IN(claim, t + .8);
    const stp = stamp(s, t + o.reveal, o.answer, { center: true, y: K.safe.y + K.safe.h * (L() ? .55 : .5) });
    const why = C(o.why, K.safe.y + K.safe.h * (L() ? .76 : .66), L() ? 36 : 42, 'serif', {}, s); IN(why, t + o.reveal + .6);
    return { q, claim, stp, why };
  }

  // ---------- characters ----------
  // Camões, drawn in code (lusiadas.md "Characters": died 1580, stylised, never an AI likeness). Ported from Ep.00.
  const CAMOES_SVG = `<defs><clipPath id="cc"><circle cx="230" cy="230" r="210"/></clipPath></defs>
   <circle cx="230" cy="230" r="215" fill="#14103a" stroke="#ffc83d" stroke-width="8"/>
   <g clip-path="url(#cc)"><path d="M120 460 Q130 330 230 320 Q330 330 340 460Z" fill="#ff4d3d"/>
   <ellipse cx="230" cy="215" rx="88" ry="105" fill="#f6efe2"/>
   <path d="M150 230 Q160 330 230 345 Q300 330 310 230 Q300 300 230 310 Q160 300 150 230Z" fill="#c9a36a"/>
   <path d="M150 190 Q230 160 310 190" stroke="#0a0f24" stroke-width="10" fill="none"/>
   <ellipse cx="265" cy="205" rx="22" ry="16" fill="#0a0f24"/>
   <path d="M175 205 h40" stroke="#0a0f24" stroke-width="9" stroke-linecap="round"/>
   ${Array.from({ length: 9 }, (_, i) => { const a = Math.PI * (1.05 + i * .11); const x = 230 + Math.cos(a) * 112, y = 200 + Math.sin(a) * 112;
    return `<ellipse cx="${x}" cy="${y}" rx="26" ry="11" fill="#3fbf6a" transform="rotate(${a * 180 / Math.PI + 90} ${x} ${y})"/>`; }).join('')}
   </g>`;

  // Pose overlays inside the avatar circle (same 460x460 space). Wink is done on the eye, not by an overlay.
  const POSES = {
    point: '<path d="M300 470 L360 330 L440 300" stroke="#f6efe2" stroke-width="34" fill="none" stroke-linecap="round" stroke-linejoin="round"/><circle cx="446" cy="298" r="22" fill="#f6efe2"/>',
    shrug: '<path d="M110 470 L70 360 L40 330 M350 470 L390 360 L420 330" stroke="#f6efe2" stroke-width="30" fill="none" stroke-linecap="round" stroke-linejoin="round"/>',
    facepalm: '<path d="M140 470 Q150 330 205 245" stroke="#f6efe2" stroke-width="34" fill="none" stroke-linecap="round"/><ellipse cx="225" cy="215" rx="58" ry="44" fill="#f6efe2" stroke="#0a0f24" stroke-width="5"/>',
  };

  /**
   * Camões avatar: enters at t0 (pop, or slides in from an edge with {from: 'left'|'right'|'bottom'}), bobs gently,
   * bobs fast while talking. talk = [[start, end], ...]; poses = [[start, end, 'point'|'shrug'|'facepalm'|'wink'], ...].
   */
  function camoes(s, t0, o = {}) {
    const size = (o.size ?? (L() ? 380 : 340)) * K.u;
    const x = o.x ?? (L() ? K.safe.x + 120 * K.u : K.safe.x + (K.safe.w - size) / 2);
    const y = o.y ?? (L() ? K.safe.y + (K.safe.h - size) / 2 : K.safe.y + K.safe.h - size - 20 * K.u);
    const overlays = Object.entries(POSES).map(([k, v]) => `<g class="pose-${k}" clip-path="url(#cc)" display="none">${v}</g>`).join('');
    const a = svg(CAMOES_SVG + overlays, { left: px(x), top: px(y), width: px(size), height: px(size) }, s, '0 0 460 460');
    const edge = { left: { x: -(x + size + 40) }, right: { x: K.W - x + 40 }, bottom: { y: K.H - y + 40 } }[o.from];
    if (edge) a.tw(t0, .7, Object.assign({ o: 1 }, edge), { x: 0, y: 0 }, 'back'); else POP(a, t0, .7);
    const talk = o.talk || [], poses = o.poses || [];
    const eye = a.querySelector('ellipse[cx="265"]'), groups = Object.fromEntries(Object.keys(POSES).map(k => [k, a.querySelector('.pose-' + k)]));
    hook(t => {
      const on = talk.some(([p, q]) => t >= p && t < q); a.firstChild.style.transform = `translateY(${Math.sin(t * (on ? 9 : 1.5)) * (on ? 6 : 8)}px)`;
      const cur = (poses.find(([p, q]) => t >= p && t < q) || [])[2];
      for (const k in groups) groups[k].setAttribute('display', cur === k ? 'inline' : 'none');
      eye.setAttribute('ry', cur === 'wink' ? 3 : 16);
    });
    a._box = { x, y, size };
    return a;
  }

  /** Speech bubble typed on beside Camões (or at an explicit position). His lines are his verses, cited. */
  function say(s, who, t, text, o = {}) {
    const b0 = who._box, size = (o.size ?? 46) * K.u;
    let x, y, w, radius;
    if (L()) { x = b0.x + b0.size + 30 * K.u; w = Math.min(1000 * K.u, K.safe.x + K.safe.w - x); y = b0.y + 20 * K.u; radius = '34px 34px 34px 8px'; }
    else { x = K.safe.x + 20 * K.u; w = K.safe.w - 40 * K.u; y = b0.y - 340 * K.u; radius = '34px 34px 8px 34px'; }
    const colour = o.colour || 'var(--gold)';
    const b = mk('', { left: px(o.x ?? x), top: px(o.y ?? y), width: px(o.w ?? w), background: 'rgba(255,255,255,.07)', border: `3px solid ${colour}`,
      borderRadius: radius, padding: `${26 * K.u}px ${36 * K.u}px` }, s);
    b.tw(t, .45, { o: 0, s: .7, y: 30 }, { o: 1, s: 1, y: 0 }, 'back');
    const tx = typed(text, { position: 'relative', fontSize: px(size) }, b, 'serif'); tx.tw(t + .25, o.dur || Math.max(.6, text.length / 34), { tc: 0 }, { tc: 1 }, 'lin');
    if (o.cite) { const c = mk(o.cite, { position: 'relative', fontSize: px(20 * K.u), marginTop: px(12 * K.u), color: colour }, b, 'mono'); c.tw(t + .4, .4, { o: 0 }, { o: 1 }); }
    return b;
  }

  /** Gods group chat: message bubbles stacking up like a phone chat. msgs = [{who, text, t, side: 'l'|'r', colour}]. */
  function godsChat(s, t, title, msgs) {
    const w = (L() ? 1150 : 920) * K.u, x = K.safe.x + (K.safe.w - w) / 2, top = K.safe.y + (L() ? 40 : 40) * K.u;
    const head = T(title, x, top, 30, 'mono', { width: px(w), textAlign: 'center', color: 'var(--gold)' }, s); IN(head, t, 20);
    let y = top + 70 * K.u;
    for (const m of msgs) {
      const av = 64 * K.u, bw = w * .72 - av - 16 * K.u, right = m.side === 'r';
      const bx = right ? x + w - bw - av - 16 * K.u : x + av + 16 * K.u, ax = right ? x + w - av : x;
      const avatar = mk((m.who || '?')[0], { left: px(ax), top: px(y + 10 * K.u), width: px(av), height: px(av), borderRadius: '50%', background: m.colour || 'var(--cyan)',
        color: 'var(--navy)', fontSize: px(32 * K.u), textAlign: 'center', lineHeight: px(av) }, s, 'h');
      POP(avatar, m.t - .7, .4);
      const side = right ? { left: 'auto', right: px(K.W - bx - bw) } : { left: px(bx) };
      const dots = mk('<span>●</span> <span>●</span> <span>●</span>', { ...side, top: px(y + 20 * K.u), padding: `${10 * K.u}px ${22 * K.u}px`, fontSize: px(22 * K.u),
        borderRadius: px(22 * K.u), background: 'rgba(255,255,255,.09)', color: 'rgba(246,239,226,.7)' }, s, 'b');
      dots.tw(m.t - .7, .2, { o: 0 }, { o: 1 }, 'lin'); dots.tw(m.t - .05, .05, {}, { o: 0 }, 'lin');
      const spans = dots.querySelectorAll('span'), t1 = m.t;
      hook(t => { if (t > t1 - .7 && t < t1) spans.forEach((sp, i) => { sp.style.opacity = .35 + .65 * Math.max(0, Math.sin((t - t1) * 10 - i)); }); });
      const b = mk(`<div class="mono" style="font-size:${18 * K.u}px;color:${m.colour || 'var(--cyan)'};letter-spacing:.12em">${m.who}</div>
        <div class="b" style="font-size:${(L() ? 40 : 38) * K.u}px;margin-top:${6 * K.u}px">${m.text}</div>`,
        { left: px(bx), top: px(y), width: px(bw), padding: `${16 * K.u}px ${24 * K.u}px`, background: m.side === 'r' ? 'rgba(31,95,191,.45)' : 'rgba(255,255,255,.09)',
          borderRadius: m.side === 'r' ? '26px 26px 6px 26px' : '26px 26px 26px 6px' }, s);
      b.tw(m.t, .4, { o: 0, y: 40 * K.u, s: .9 }, { o: 1, y: 0, s: 1 }, 'back'); K.cue('pop', m.t);
      y += (L() ? 170 : 150) * K.u;
    }
    return head;
  }

  // ---------- maps ----------
  /**
   * Route map: a simple stylised coastline-free chart with stops (normalised 0..1 coordinates) joined by a dashed route
   * that draws itself; each stop appears at its time. stops = [{name, x, y, t}]. A stop with no t was reached in an
   * earlier episode: it and its leg are drawn from the start (the season map that grows each episode).
   */
  function routeMap(s, t0, stops, o = {}) {
    stops = stops.map(st => Object.assign({}, st, { t: st.t ?? t0 }));
    const box = L() ? { x: K.safe.x + K.safe.w * .3, y: K.safe.y + 40 * K.u, w: K.safe.w * .68, h: K.safe.h - 80 * K.u }
      : { x: K.safe.x, y: K.safe.y + K.safe.h * .18, w: K.safe.w, h: K.safe.h * .7 };
    const P = st => [box.x + st.x * box.w, box.y + st.y * box.h];
    const grid = Array.from({ length: 9 }, (_, i) => `<line x1="${box.x + i * box.w / 8}" y1="${box.y}" x2="${box.x + i * box.w / 8}" y2="${box.y + box.h}"/>
      <line x1="${box.x}" y1="${box.y + i * box.h / 8}" x2="${box.x + box.w}" y2="${box.y + i * box.h / 8}"/>`).join('');
    const pts = stops.map(P), d = 'M' + pts.map(p => p.map(v => v.toFixed(1)).join(' ')).join(' L');
    const g = svg(`<g stroke="rgba(61,225,255,.12)" stroke-width="2">${grid}</g>
      <path id="route" d="${d}" fill="none" stroke="var(--gold)" stroke-width="${7 * K.u}" stroke-linecap="round" stroke-dasharray="1 0"/>
      <circle id="boat" r="${16 * K.u}" fill="var(--coral)" stroke="var(--cream)" stroke-width="${4 * K.u}"/>`,
      { left: 0, top: 0, width: px(K.W), height: px(K.H) }, s);
    g.tw(t0, .6, { o: 0 }, { o: 1 }, 'lin');
    const path = g.querySelector('#route'), boat = g.querySelector('#boat');
    // Segment lengths without layout reads, so the result does not depend on render timing.
    const seg = pts.slice(1).map((p, i) => Math.hypot(p[0] - pts[i][0], p[1] - pts[i][1])), total = seg.reduce((a, b) => a + b, 0);
    path.setAttribute('stroke-dasharray', `${total} ${total}`);
    hook(t => {
      let i = 0; while (i < stops.length - 1 && t >= stops[i + 1].t) i++;
      let done = seg.slice(0, i).reduce((a, b) => a + b, 0), pos = pts[i];
      if (i < stops.length - 1) {
        const p = K.E.io(K.clamp((t - stops[i].t - .6) / Math.max(.1, stops[i + 1].t - stops[i].t - .6)));
        done += seg[i] * p; pos = [pts[i][0] + (pts[i + 1][0] - pts[i][0]) * p, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * p];
      }
      path.setAttribute('stroke-dashoffset', (total - done).toFixed(1));
      boat.setAttribute('cx', pos[0].toFixed(1)); boat.setAttribute('cy', pos[1].toFixed(1));
    });
    stops.forEach((st, i) => {
      const [x, y] = P(st);
      const dot = mk('', { left: px(x - 12 * K.u), top: px(y - 12 * K.u), width: px(24 * K.u), height: px(24 * K.u), borderRadius: '50%', background: 'var(--cream)' }, s);
      POP(dot, st.t);
      const lbl = T(st.name, x + 24 * K.u, y - 44 * K.u, L() ? 28 : 30, 'mono', { color: 'var(--cream)', whiteSpace: 'nowrap' }, s); IN(lbl, st.t + .1, 20);
      if (st.year) { const yr = T(st.year, x + 24 * K.u, y - 8 * K.u, 22, 'mono', { color: 'var(--gold)' }, s); IN(yr, st.t + .2, 20); }
    });
    return g;
  }

  // ---------- sting and cards ----------
  /** Title sting (replaces an intro, C7): episode tag, letter-by-letter OS LUSÍADAS, underline, subtitle. */
  function titleSting(s, t, o) {
    stripes(s, t, t + 12);
    const top = L() ? K.H * .21 : K.H * .3;
    const tg = C(o.tag, top, 26, 'mono', { color: 'var(--cyan)' }, s); POP(tg, t + .5);
    const big = L() ? 230 : 150, row = mk('', { left: 0, top: px(top + 150 * K.u), width: px(K.W), textAlign: 'center' }, s);
    const words = L() ? [o.title] : o.title.split(' ');
    let i = 0;
    words.forEach((word, wi) => {
      const line = mk('', { position: 'relative', display: 'block', marginTop: wi ? px(big * .22 * K.u) : 0 }, row);
      [...word].forEach(ch => {
        const sp = mk(ch === ' ' ? '&nbsp;' : ch, { position: 'relative', display: 'inline-block', fontSize: px(big * K.u), color: 'var(--gold)' }, line, 'h');
        sp.tw(t + .9 + i++ * .07, .7, { o: 0, y: 120 * K.u, r: -12 }, { o: 1, y: 0, r: 0 }, 'back');
      });
    });
    const titleH = big * K.u * (.92 * words.length + .22 * (words.length - 1));
    const ulW = (L() ? 800 : 640) * K.u;
    const ul = mk('', { left: px((K.W - ulW) / 2), top: px(top + 150 * K.u + titleH + 30 * K.u), width: px(ulW), height: px(10 * K.u), background: 'var(--coral)' }, s);
    WIPE(ul, t + 2.1, .6);
    const sub = C(o.sub, top + 150 * K.u + titleH + 80 * K.u, L() ? 44 : 46, 'b', {}, s); IN(sub, t + 2.5);
    K.flash(t + .9, .35, .3); K.cue('sting', t);
    return { tg, row, ul, sub };
  }

  /** Next-episode teaser card (last 10 s, C5). */
  function teaser(s, t, o) {
    const a = C('NEXT TIME', K.safe.y + K.safe.h * .25, 34, 'mono', { color: 'var(--cyan)' }, s); IN(a, t);
    const b = C(o.title, K.safe.y + K.safe.h * .36, L() ? 110 : 96, 'h', { color: 'var(--gold)' }, s); b.tw(t + .5, .8, { o: 0, s: .7, b: 10 }, { o: 1, s: 1, b: 0 }, 'back');
    const c = C(o.hook, K.safe.y + K.safe.h * (L() ? .62 : .6), L() ? 48 : 52, 'serif', {}, s); IN(c, t + 1.4);
    return { a, b, c };
  }

  K.c = { characterCard, armillary, stripes, azulejo, waves, rain, chapterTag, statCard, verse, stamp, factOrLegend, camoes, say, godsChat, routeMap, titleSting, teaser };
})();
