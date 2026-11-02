// Renders out/preview_model.json from several angles into preview.png using three.js + headless Chromium.
// Usage: (cd render && npm install) && node render/render.mjs
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const model = readFileSync(join(root, 'out', 'preview_model.json'), 'utf8');
const threeUrl = pathToFileURL(join(here, 'node_modules', 'three', 'build', 'three.module.js')).href;

const html = `<!doctype html><html><body style="margin:0;background:#1b1d22">
<canvas id="c" width="1600" height="1000"></canvas>
<script type="importmap">{"imports":{"three":"${threeUrl}"}}</script>
<script type="module">
import * as THREE from 'three';
const M = ${model};
const W = 1600, H = 1000;
const renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('c'), antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1);
renderer.autoClear = false;
renderer.setClearColor(0x1b1d22);

const loader = new THREE.TextureLoader();
const tex = {};
await Promise.all(Object.entries(M.textures).map(([k, src]) => new Promise(res => loader.load(src, t => {
  t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.SRGBColorSpace; tex[k] = t; res();
}))));

// Minecraft-style directional face shading
const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };
function corners(f, a, b) {
  const [x1, y1, z1] = a, [x2, y2, z2] = b;
  return {
    north: [[x2,y2,z1],[x1,y2,z1],[x1,y1,z1],[x2,y1,z1]],
    south: [[x1,y2,z2],[x2,y2,z2],[x2,y1,z2],[x1,y1,z2]],
    east:  [[x2,y2,z2],[x2,y2,z1],[x2,y1,z1],[x2,y1,z2]],
    west:  [[x1,y2,z1],[x1,y2,z2],[x1,y1,z2],[x1,y1,z1]],
    up:    [[x1,y2,z1],[x2,y2,z1],[x2,y2,z2],[x1,y2,z2]],
    down:  [[x1,y1,z2],[x2,y1,z2],[x2,y1,z1],[x1,y1,z1]],
  }[f];
}
const scene = new THREE.Scene();
const group = new THREE.Group();
for (const el of M.elements) {
  for (const [f, face] of Object.entries(el.faces)) {
    const c = corners(f, el.from, el.to);
    const [u1, v1, u2, v2] = face.uv.map(n => n / 16);
    const uv = [[u1, 1 - v1], [u2, 1 - v1], [u2, 1 - v2], [u1, 1 - v2]];
    const g = new THREE.BufferGeometry();
    const idx = [0, 3, 2, 0, 2, 1];
    g.setAttribute('position', new THREE.Float32BufferAttribute(idx.flatMap(i => c[i]), 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(idx.flatMap(i => uv[i]), 2));
    const s = el.glow ? 1 : SHADE[f];
    const mat = new THREE.MeshBasicMaterial({ map: tex[face.texture], color: new THREE.Color(s, s, s), side: THREE.DoubleSide });
    group.add(new THREE.Mesh(g, mat));
  }
}
// block grid on the floor (each cell = 1 block) to show the footprint
const grid = new THREE.GridHelper(64, 4, 0x3a3f4a, 0x3a3f4a);
grid.position.set(16, -0.01, 8);
scene.add(grid);
group.position.set(0, 0, 0);
scene.add(group);

const center = new THREE.Vector3(16, 15, 8);
const views = [
  { x: 0, y: 0, w: 1000, h: 1000, az: 38, el: 24, d: 74, label: 'front' },
  { x: 1000, y: 0, w: 600, h: 500, az: 215, el: 24, d: 78, label: 'back' },
  { x: 1000, y: 500, w: 600, h: 500, az: 95, el: 42, d: 80, label: 'conveyor side' },
];
renderer.clear();
for (const v of views) {
  const cam = new THREE.PerspectiveCamera(35, v.w / v.h, 0.1, 1000);
  const az = v.az * Math.PI / 180, el = v.el * Math.PI / 180;
  // azimuth 0 = looking at the north face (front, z = 0)
  cam.position.set(center.x + Math.sin(az) * Math.cos(el) * v.d,
                   center.y + Math.sin(el) * v.d,
                   center.z - Math.cos(az) * Math.cos(el) * v.d);
  cam.lookAt(center);
  renderer.setViewport(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissor(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissorTest(true);
  renderer.clear();
  renderer.render(scene, cam);
}
window.__done = true;
</script></body></html>`;

const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--allow-file-access-from-files'] });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
page.on('console', m => console.log('[page]', m.text()));
page.on('pageerror', e => console.log('[pageerror]', e.message));
const tmp = join(here, '.render.html');
writeFileSync(tmp, html);
await page.goto(pathToFileURL(tmp).href);
await page.waitForFunction(() => window.__done === true, null, { timeout: 30000 });
await page.locator('#c').screenshot({ path: join(root, 'preview.png') });
await browser.close();
console.log('wrote', join(root, 'preview.png'));
