// Renders lit quads (crop_blocks_preview.py): each quad carries its corners, uvs and a brightness per corner, already
// worked out the way the game lights it. Nearest textures, colour maths in sRGB like the game, back faces culled,
// cut-out alpha. Several cells (each its own camera) in one picture.
// Usage: node render/quads.mjs <scene.json> <out.png>   (needs: cd render && npm install, and playwright)
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const [input, output] = process.argv.slice(2).map(p => resolve(p));
const data = readFileSync(input, 'utf8');
const { W, H } = JSON.parse(data);
const threeUrl = pathToFileURL(join(here, 'node_modules', 'three', 'build', 'three.module.js')).href;

const html = `<!doctype html><html><body style="margin:0">
<canvas id="c" width="${W}" height="${H}"></canvas>
<script type="importmap">{"imports":{"three":"${threeUrl}"}}</script>
<script type="module">
import * as THREE from 'three';
const D = ${data};
const renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('c'), antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1); renderer.autoClear = false;
renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
renderer.setClearColor(new THREE.Color().setRGB(0.62, 0.76, 0.95, THREE.LinearSRGBColorSpace));
const loader = new THREE.TextureLoader();
const tex = {};
await Promise.all(Object.entries(D.textures).map(([k, src]) => new Promise(res => loader.load(src, t => {
  t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.generateMipmaps = false;
  t.colorSpace = THREE.NoColorSpace; tex[k] = t; res();
}))));
const mats = {};
const mat = k => mats[k] || (mats[k] = new THREE.MeshBasicMaterial({ map: tex[k], vertexColors: true, side: THREE.FrontSide, alphaTest: 0.1 }));
for (const cell of D.cells) {
  const scene = new THREE.Scene();
  const groups = {};
  for (const q of cell.quads) (groups[q.tex] ||= []).push(q);
  for (const [k, qs] of Object.entries(groups)) {
    const pos = [], uv = [], col = [];
    for (const q of qs) {
      for (const i of [0, 3, 2, 0, 2, 1]) {
        pos.push(...q.p[i]); uv.push(q.uv[i][0] / 16, 1 - q.uv[i][1] / 16); col.push(...q.c[i]);
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    scene.add(new THREE.Mesh(g, mat(k)));
  }
  const cam = new THREE.PerspectiveCamera(cell.cam.fov, D.cw / D.ch, 0.05, 100);
  cam.position.set(...cell.cam.eye); cam.lookAt(...cell.cam.at);
  const y = D.H - cell.y - D.ch;
  renderer.setViewport(cell.x, y, D.cw, D.ch); renderer.setScissor(cell.x, y, D.cw, D.ch); renderer.setScissorTest(true);
  renderer.clear(); renderer.render(scene, cam);
}
window.__done = true;
</script></body></html>`;

const tmp = join(here, '.render.html');
writeFileSync(tmp, html);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--allow-file-access-from-files'] });
try {
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  page.on('pageerror', e => console.error('[pageerror]', e.message));
  page.on('console', m => { if (m.type() === 'error') console.error('[console]', m.text()); });
  await page.goto(pathToFileURL(tmp).href);
  await page.waitForFunction(() => window.__done === true, null, { timeout: 180000 });
  await page.locator('#c').screenshot({ path: output });
} finally {
  await browser.close();
  rmSync(tmp, { force: true });
}
console.log('render  ' + output);
