// Motion kit tests: run with `npm test` from motion-kit/ (needs Playwright's Chromium and FFmpeg).
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { open, shot, check, renderVideo, renderStills } = require('../render/render.js');

const DEMO = path.join(__dirname, '../examples/lusiadas-demo/scene.html');
const BROKEN = path.join(__dirname, 'fixtures/broken.html');
const TIMES = [0.5, 3.5, 10, 13, 17, 27, 34, 40.5, 44.8, 50.5, 55];

for (const frame of ['landscape', 'vertical']) {
  test(`${frame}: lays out at the frame size with no page errors`, async () => {
    const { browser, page, info, errors } = await open(DEMO, frame);
    try {
      assert.deepStrictEqual([info.frame.width, info.frame.height], frame === 'landscape' ? [1920, 1080] : [1080, 1920]);
      assert.strictEqual(info.duration, 58);
      for (const t of TIMES) await shot(page, t);
      check(errors);
    } finally { await browser.close(); }
  });

  test(`${frame}: the same time always gives the same frame, whatever was drawn before`, async () => {
    const a = await open(DEMO, frame), b = await open(DEMO, frame);
    try {
      // Page a seeks forwards; page b jumps somewhere else first. Every frame must still match byte for byte.
      for (const [i, t] of TIMES.entries()) {
        const fa = await shot(a.page, t, 100);
        await shot(b.page, TIMES[TIMES.length - 1 - i], 100);
        const fb = await shot(b.page, t, 100);
        assert.ok(fa.equals(fb), `frame at ${t}s differs between runs`);
      }
    } finally { await a.browser.close(); await b.browser.close(); }
  });
}

test('every element stays inside the frame', async () => {
  for (const frame of ['landscape', 'vertical']) {
    const { browser, page } = await open(DEMO, frame);
    try {
      for (const t of TIMES) {
        const out = await page.evaluate(t => {
          window.seek(t);
          const W = window.FRAME.width, H = window.FRAME.height, bad = [];
          for (const el of document.querySelectorAll('#stage .h, #stage .b, #stage .serif, #stage .mono, #stage .pill')) {
            if (!el.offsetParent || !el.textContent.trim()) continue;
            const r = el.getBoundingClientRect();
            if (r.width && (r.left < -2 || r.top < -2 || r.right > W + 2 || r.bottom > H + 2)) bad.push(el.textContent.slice(0, 40));
          }
          return bad;
        }, t);
        assert.deepStrictEqual(out, [], `${frame} at ${t}s: text outside the frame`);
      }
    } finally { await browser.close(); }
  }
});

test('the scene exposes its sound cues in time order', async () => {
  const { browser, info } = await open(DEMO, 'landscape');
  await browser.close();
  assert.ok(info.cues.length > 5);
  assert.ok(info.cues.some(c => c.id === 'stamp') && info.cues.some(c => c.id === 'sting'));
  for (let i = 1; i < info.cues.length; i++) assert.ok(info.cues[i].t >= info.cues[i - 1].t);
});

test('a scene with a script error fails the render', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'kit-'));
  await assert.rejects(renderVideo({ scene: BROKEN, frame: 'landscape', fps: 10, quality: 80, out: path.join(dir, 'x.mp4') }), /scene errors/);
});

test('renders a short MP4 and a contact sheet', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'kit-'));
  const out = path.join(dir, 'clip.mp4');
  await renderVideo({ scene: DEMO, frame: 'vertical', fps: 10, quality: 80, from: 0, to: 1, out });
  assert.ok(fs.statSync(out).size > 10000);
  assert.ok(JSON.parse(fs.readFileSync(path.join(dir, 'clip.cues.json'))).frame.width === 1080);
  const sheet = path.join(dir, 'sheet.jpg');
  await renderStills({ scene: DEMO, frame: 'landscape', stills: '1,5', outdir: dir, quality: 80, sheet });
  assert.ok(fs.statSync(sheet).size > 5000);
});
