# Motion kit

The scene kit for Pipeline D (`docs/pipeline-d/README.md`, "The scene kit"). Claude writes each scene as one HTML file
against this kit; the renderer draws every frame in headless Chromium and FFmpeg encodes them. Version 1.0.0 is
seeded from the Os Lusíadas Ep.00 engine (`reference/lusiadas-ep00/ep0.html`) and adds the series components from
`docs/series/lusiadas.md`.

## One scene file, two frames

A scene lays itself out against the stage, so the same file renders at 1920×1080 or 1080×1920. The frame comes from
the scene's `K.setup({frame})` and can be overridden with `?frame=landscape|vertical` (B2 re-renders long-form scenes
as Shorts this way, decision C8). After setup the scene reads:

| Name | Meaning |
| --- | --- |
| `K.W`, `K.H` | Stage size in px |
| `K.safe` | `{x, y, w, h}`: landscape title-safe area, or the vertical safe zone clear of Shorts/TikTok/Reels buttons and captions |
| `K.u` | One design unit: 1 px at 1080 px on the short side, so sizes read the same in both frames |
| `K.landscape` | `true` in 16:9, for layout choices a scene makes per frame |

Add `&safe=1` to the URL to outline the safe area while designing.

## Time contract

- A scene builds its whole timeline once, then calls `K.ready()`.
- `K.ready()` waits for the bundled fonts and then sets `window.DURATION`, `window.FRAME`, `window.CUES`,
  `window.seek(t)` and finally `window.READY = true`.
- `seek(t)` draws the exact frame for time `t`. No timers, no `requestAnimationFrame`, no `Date`, no `Math.random`
  (use `K.rng(seed)`), no network. The same `t` always gives the same pixels, in any order of seeks (tested).
- `window.CUES` lists sound cues (`{id, t}`) for flashes, stamps, chat pops and the title sting, so assembly can place
  library sound effects on the same frames (C11). The renderer writes them next to the video as `<name>.cues.json`.

## Core (`kit/core.js`)

| Call | What it does |
| --- | --- |
| `K.scene(s, e, bg)` | Full-frame container shown between `s` and `e`, with a short fade at each end |
| `K.mk(html, style, parent, cls)` | Positioned element; `.tw(t0, dur, from, to, ease)` adds a tween |
| `K.T(html, x, y, size, cls)`, `K.C(html, y, size, cls)` | Text at a point, or centred across the safe area (size in design units) |
| `K.typed(text, style, parent, cls)` | Text typed on by tweening `tc` from 0 to 1 |
| `K.svg(inner, style, parent, viewBox)` | SVG element |
| `K.IN`, `K.POP`, `K.OUT`, `K.WIPE` | Standard entrances and exits |
| `K.hook(f)` | Per-frame function of time, for paths and counters tweens can't express |
| `K.flash(t)`, `K.cue(id, t)` | White flash with a hit cue; a bare sound cue |
| `K.at(t0, build)` | Builds a block in local time, placed at `t0` |

Tweenable properties: `x`, `y` (px offsets), `s` (scale), `r` (degrees), `o` (opacity), `b` (blur px), `cl`
(left-to-right reveal) and `tc` (typed fraction). Easings: `lin`, `out`, `in`, `io`, `back` (overshoot), `el` (spring).

## Series components (`kit/components.js`, as `K.c.*`)

| Component | Spec item |
| --- | --- |
| `titleSting(s, t, {tag, title, sub})` | Per-episode title sting (C7) |
| `chapterTag(s, t, n, title)` | Chapter tag pill |
| `characterCard(s, t, {name, years, role, stats})` | Character stat card with bars |
| `statCard(s, t, {value, unit, label, prefix})` | Count-up |
| `routeMap(s, t0, stops)` | Season route map; stops without `t` were reached in earlier episodes and start drawn |
| `godsChat(s, t, title, msgs)` | Gods' chat with avatars, typing dots and timed messages |
| `verse(s, t, {ref, pt, en, translator})` | Typed verse, Portuguese then English, with the citation and translator credit |
| `stamp(s, t, kind)` | LEGEND / DOCUMENTED / TRADITION / THE POEM SAYS stamp with a cue |
| `factOrLegend(s, t, {claim, reveal, answer, why})` | Fact or Legend? segment |
| `camoes(s, t0, {from, talk, poses})`, `say(s, cam, t, text, {cite})` | Camões host rig (code-drawn, C3/C10) with poses point, shrug, facepalm, wink, and speech bubbles |
| `azulejo`, `armillary`, `stripes`, `waves`, `rain` | Backdrops |
| `teaser(s, t, {title, hook})` | Next-episode teaser (C5) |

## Rendering

```
cd motion-kit
node render/render.js examples/lusiadas-demo/scene.html --frame landscape --out demo.mp4
node render/render.js examples/lusiadas-demo/scene.html --frame vertical --stills 3,10,20 --outdir shots --sheet shots/sheet.jpg
npm test
```

Needs Node 18+, Playwright with Chromium, and FFmpeg on the PATH. Any page error, console error or failed request
fails the render, so a broken scene never produces a video. Rendered files belong in the assets directory, never in
the repo.

## Fonts

Inter, JetBrains Mono and Playfair Display (SIL Open Font License 1.1), bundled in `kit/fonts/` with their licence
files. No fonts or scripts are fetched at render time.

## Changing the kit

Bump `K.version` in `kit/core.js` with any change that alters pixels. The version goes into each scene's cache hash,
so a kit change re-renders only the scenes that use it (Pipeline D scenario 17).
