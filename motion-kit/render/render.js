#!/usr/bin/env node
// Renders a motion-kit scene to MP4 (or to stills and a contact sheet) with headless Chromium and FFmpeg.
// The scene must follow the kit's time contract: window.READY, window.DURATION, window.seek(t) (see ../README.md).
//
//   node render.js <scene.html> --frame landscape|vertical --out out.mp4 [--fps 30] [--from 0] [--to DURATION]
//   node render.js <scene.html> --frame vertical --stills 1,5.5,12 --outdir shots/ [--sheet sheet.jpg]
//
// Any JavaScript error or failed request in the page fails the render, so a broken scene never produces a video.
'use strict';
const { chromium } = require('playwright');
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const { pathToFileURL } = require('url');

const FRAMES = { landscape: [1920, 1080], vertical: [1080, 1920] };

function parseArgs(argv) {
  const a = { fps: 30, frame: 'landscape', quality: 92 };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i];
    if (k.startsWith('--')) a[k.slice(2)] = argv[++i]; else rest.push(k);
  }
  a.scene = rest[0];
  if (!a.scene) throw new Error('usage: render.js <scene.html> --frame landscape|vertical (--out file.mp4 | --stills t1,t2 --outdir dir)');
  if (!FRAMES[a.frame]) throw new Error('unknown frame ' + a.frame);
  a.fps = +a.fps;
  return a;
}

/** Opens the scene, waits for READY and returns {browser, page, info, errors}. */
async function open(scene, frame) {
  const [width, height] = FRAMES[frame];
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', e => errors.push('page error: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console error: ' + m.text()); });
  page.on('requestfailed', r => errors.push('request failed: ' + r.url()));
  const url = pathToFileURL(path.resolve(scene)).href + '?frame=' + frame;
  await page.goto(url);
  try {
    await page.waitForFunction(() => window.READY === true, null, { timeout: 30000 });
  } catch (e) {
    await browser.close();
    throw new Error(`scene never became READY${errors.length ? ': ' + errors.join('; ') : ''}`);
  }
  const info = await page.evaluate(() => ({ duration: window.DURATION, frame: window.FRAME, cues: window.CUES, version: window.K && window.K.version }));
  if (info.frame.width !== width || info.frame.height !== height) throw new Error(`scene laid out ${info.frame.width}x${info.frame.height}, expected ${width}x${height}`);
  return { browser, page, info, errors };
}

function check(errors) { if (errors.length) throw new Error('scene errors:\n  ' + errors.join('\n  ')); }

async function shot(page, t, quality = 92) {
  try {
    await page.evaluate(t => window.seek(t), t);
  } catch (e) {
    throw new Error(`scene errors:\n  seek(${t}) threw: ${e.message.split('\n')[0]}`);
  }
  return page.screenshot({ type: 'jpeg', quality });
}

async function renderVideo(a) {
  const { browser, page, info, errors } = await open(a.scene, a.frame);
  try {
    const from = +(a.from || 0), to = Math.min(+(a.to || info.duration), info.duration);
    const n = Math.round((to - from) * a.fps);
    const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(a.fps), '-c:v', 'mjpeg', '-i', '-',
      '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'medium', '-crf', '18', '-movflags', '+faststart', a.out], { stdio: ['pipe', 'inherit', 'inherit'] });
    const done = new Promise((res, rej) => ff.on('close', c => c === 0 ? res() : rej(new Error('ffmpeg exited ' + c))));
    try {
      for (let i = 0; i < n; i++) {
        const buf = await shot(page, from + i / a.fps, a.quality);
        if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
        if (i % (a.fps * 5) === 0) { check(errors); process.stderr.write(`\r${a.frame} ${(from + i / a.fps).toFixed(1)}/${to.toFixed(1)} s`); }
      }
      check(errors);
    } catch (e) {
      // Stop the encoder and drop the partial file: a broken scene must not leave a video behind.
      ff.kill('SIGKILL'); await done.catch(() => {}); fs.rmSync(a.out, { force: true });
      throw e;
    }
    ff.stdin.end();
    await done;
    // The cue sheet travels with the video so assembly can place sound effects on the same times.
    fs.writeFileSync(a.out.replace(/\.mp4$/, '') + '.cues.json', JSON.stringify({ frame: info.frame, fps: a.fps, from, to, kit: info.version, cues: info.cues }, null, 2));
    process.stderr.write(`\nwrote ${a.out} (${n} frames)\n`);
  } finally { await browser.close(); }
}

async function renderStills(a) {
  const { browser, page, errors } = await open(a.scene, a.frame);
  const files = [];
  try {
    fs.mkdirSync(a.outdir, { recursive: true });
    for (const t of String(a.stills).split(',').map(Number)) {
      const f = path.join(a.outdir, `${a.frame}-${t.toFixed(2).padStart(6, '0')}.jpg`);
      fs.writeFileSync(f, await shot(page, t, a.quality));
      files.push({ t, f });
    }
    check(errors);
  } finally { await browser.close(); }
  if (a.sheet) contactSheet(files, a.sheet, a.frame);
  return files;
}

/** Tiles stills into one labelled JPEG with FFmpeg (time in the corner of each tile). */
function contactSheet(files, out, frame) {
  const cols = frame === 'landscape' ? 3 : 4, rows = Math.ceil(files.length / cols);
  const [w, h] = frame === 'landscape' ? [640, 360] : [360, 640];
  const inputs = files.flatMap(x => ['-i', x.f]);
  const scaled = files.map((x, i) => `[${i}:v]scale=${w}:${h},drawtext=text='${x.t.toFixed(1)} s':x=10:y=10:fontsize=24:fontcolor=white:box=1:boxcolor=black@0.6[v${i}]`);
  const pads = [];
  for (let i = files.length; i < rows * cols; i++) pads.push(`color=c=black:s=${w}x${h}:d=1[v${i}]`);
  const layout = Array.from({ length: rows * cols }, (_, i) => `${(i % cols) * w}_${Math.floor(i / cols) * h}`).join('|');
  const all = Array.from({ length: rows * cols }, (_, i) => `[v${i}]`).join('');
  const graph = [...scaled, ...pads, `${all}xstack=inputs=${rows * cols}:layout=${layout}[out]`].join(';');
  const r = spawnSync('ffmpeg', ['-y', '-loglevel', 'error', ...inputs, '-filter_complex', graph, '-map', '[out]', '-frames:v', '1', '-q:v', '3', out], { stdio: 'inherit' });
  if (r.status !== 0) throw new Error('contact sheet failed');
}

module.exports = { open, shot, check, renderVideo, renderStills, contactSheet, FRAMES };

if (require.main === module) {
  const a = parseArgs(process.argv.slice(2));
  (a.stills ? renderStills(a) : renderVideo(a)).catch(e => { console.error(e.message); process.exit(1); });
}
