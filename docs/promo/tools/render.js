// Usage:
//   node docs/promo/tools/render.js stills <outdir> 1.5 8 12 ...   → PNG stills at those seconds
//   node docs/promo/tools/render.js video  <out.mp4>               → 1920×1080 30fps H.264 (needs ffmpeg)
// PAGE=vehicles/stsv_vehicles.html node ... renders the other promo (path relative to docs/promo).
const { chromium } = require('playwright');
const path = require('path'); const fs = require('fs'); const http = require('http'); const { spawn } = require('child_process');
const root = path.resolve(__dirname, '..');
const types = { '.html': 'text/html; charset=utf-8', '.png': 'image/png', '.woff2': 'font/woff2', '.js': 'text/javascript' };
const server = http.createServer((q, r) => {
  const f = path.join(root, decodeURIComponent(q.url.split('?')[0]));
  if (!f.startsWith(root) || !fs.existsSync(f)) { r.writeHead(404); return r.end(); }
  r.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); fs.createReadStream(f).pipe(r);
});
(async () => {
  const [mode, out, ...rest] = process.argv.slice(2);
  await new Promise(res => server.listen(0, res));
  const port = server.address().port;
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  page.on('console', m => { if (m.type() === 'error' || m.type() === 'warning') console.log('[page]', m.text()); });
  page.on('pageerror', e => console.log('[pageerror]', e.message));
  const pagePath = process.env.PAGE || 'stsv_promo.html';
  await page.goto(`http://127.0.0.1:${port}/${pagePath}?capture=1`);
  await page.waitForFunction(() => { const p = window.STSV || window.PROMO; return p && p.ready; });
  await page.evaluate(() => { window.__P = window.STSV || window.PROMO; });
  const grab = async t => {
    const b64 = await page.evaluate(t => { window.__P.render(t); return document.getElementById('stage').toDataURL('image/png').split(',')[1]; }, t);
    return Buffer.from(b64, 'base64');
  };
  if (mode === 'stills') {
    fs.mkdirSync(out, { recursive: true });
    for (const s of rest) fs.writeFileSync(path.join(out, `t${String(s).padStart(5, '0')}.png`), await grab(parseFloat(s)));
  } else {
    const fps = 30, dur = await page.evaluate(() => window.__P.DURATION);
    const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(fps), '-c:v', 'png', '-i', '-',
      '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-pix_fmt', 'yuv420p', '-tune', 'animation', '-movflags', '+faststart', out], { stdio: ['pipe', 'inherit', 'inherit'] });
    const n = Math.round(dur * fps);
    for (let i = 0; i < n; i++) {
      const buf = await grab(i / fps);
      if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
      if (i % 150 === 0) console.log(`frame ${i}/${n}`);
    }
    ff.stdin.end(); await new Promise(r => ff.on('close', r));
  }
  await browser.close(); server.close();
})();
