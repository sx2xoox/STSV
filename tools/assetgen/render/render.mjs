// Renders a model preview JSON (from build.py) to a PNG from three angles, Minecraft-style face shading.
// Usage: node render/render.mjs <model.preview.json> <out.png>
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright');
const here = dirname(fileURLToPath(import.meta.url));
const [input, output] = process.argv.slice(2).map(p => resolve(p));
const model = readFileSync(input, 'utf8');
// optional "size": [width, height] of the picture (default 1600 x 1000)
const [CW, CH] = JSON.parse(model).size || [1600, 1000];
const threeUrl = pathToFileURL(join(here, 'node_modules', 'three', 'build', 'three.module.js')).href;

const html = `<!doctype html><html><body style="margin:0;background:#1b1d22">
<canvas id="c" width="${CW}" height="${CH}"></canvas>
<script type="importmap">{"imports":{"three":"${threeUrl}"}}</script>
<script type="module">
import * as THREE from 'three';
const M = ${model};
const W = ${CW}, H = ${CH};
const renderer = new THREE.WebGLRenderer({ canvas: document.getElementById('c'), antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(1); renderer.autoClear = false; renderer.setClearColor(0x1b1d22);
const loader = new THREE.TextureLoader();
const tex = {};
await Promise.all(Object.entries(M.textures).map(([k, src]) => new Promise(res => loader.load(src, t => {
  t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.SRGBColorSpace; tex[k] = t; res();
}))));
const SHADE = { up: 1.0, down: 0.5, north: 0.8, south: 0.8, east: 0.6, west: 0.6 };
function corners(f, a, b) {
  const [x1, y1, z1] = a, [x2, y2, z2] = b;
  return {
    north: [[x2,y2,z1],[x1,y2,z1],[x1,y1,z1],[x2,y1,z1]], south: [[x1,y2,z2],[x2,y2,z2],[x2,y1,z2],[x1,y1,z2]],
    east: [[x2,y2,z2],[x2,y2,z1],[x2,y1,z1],[x2,y1,z2]],  west: [[x1,y2,z1],[x1,y2,z2],[x1,y1,z2],[x1,y1,z1]],
    up: [[x1,y2,z1],[x2,y2,z1],[x2,y2,z2],[x1,y2,z2]],    down: [[x1,y1,z2],[x2,y1,z2],[x2,y1,z1],[x1,y1,z1]],
  }[f];
}
const scene = new THREE.Scene();
const box = new THREE.Box3();
for (const el of M.elements) {
  let rot = null;
  if (el.rotation) {
    const [axis, angle, origin] = el.rotation;
    const ax = { x: new THREE.Vector3(1,0,0), y: new THREE.Vector3(0,1,0), z: new THREE.Vector3(0,0,1) }[axis];
    rot = { m: new THREE.Matrix4().makeRotationAxis(ax, angle * Math.PI / 180), o: new THREE.Vector3(...origin) };
  }
  for (const [f, face] of Object.entries(el.faces)) {
    const c = corners(f, el.from, el.to).map(p => {
      const v = new THREE.Vector3(...p);
      if (rot) v.sub(rot.o).applyMatrix4(rot.m).add(rot.o);
      box.expandByPoint(v);
      return [v.x, v.y, v.z];
    });
    const [u1, v1, u2, v2] = face.uv.map(n => n / 16);
    const uv = [[u1, 1 - v1], [u2, 1 - v1], [u2, 1 - v2], [u1, 1 - v2]];
    const g = new THREE.BufferGeometry();
    const idx = [0, 3, 2, 0, 2, 1];
    g.setAttribute('position', new THREE.Float32BufferAttribute(idx.flatMap(i => c[i]), 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(idx.flatMap(i => uv[i]), 2));
    const s = el.glow || el.shade === false ? 1 : SHADE[f];
    scene.add(new THREE.Mesh(g, new THREE.MeshBasicMaterial({ map: tex[face.texture], color: new THREE.Color(s, s, s), side: THREE.FrontSide, alphaTest: 0.1 })));
  }
}
const center = M.center ? new THREE.Vector3(...M.center) : box.getCenter(new THREE.Vector3());
const radius = M.radius || box.getSize(new THREE.Vector3()).length() / 2;
if (M.grid !== false) {
  // "gridAt": [x, z] where grid lines cross (furniture: [0, 0], so the lines are the cells' edges)
  const grid = new THREE.GridHelper(96, 6, 0x3a3f4a, 0x3a3f4a);
  const [gx, gz] = M.gridAt || [8, 8];
  grid.position.set(gx, -0.01, gz);
  scene.add(grid);
}
const views = M.views || [
  { x: 0, y: 0, w: 1000, h: 1000, az: 38, el: 24, k: 2.6 },
  { x: 1000, y: 0, w: 600, h: 500, az: 215, el: 24, k: 2.9 },
  { x: 1000, y: 500, w: 600, h: 500, az: 95, el: 42, k: 2.9 },
];
for (const v of views) {
  // a view may look at its own centre with its own radius and field of view (scale sheets: one scene, many pieces)
  const vc = v.center ? new THREE.Vector3(...v.center) : center, vr = v.radius || radius;
  const cam = new THREE.PerspectiveCamera(v.fov || 35, v.w / v.h, 0.1, v.far || 1000);
  const az = v.az * Math.PI / 180, el = v.el * Math.PI / 180, d = vr * v.k;
  cam.position.set(vc.x + Math.sin(az) * Math.cos(el) * d, vc.y + Math.sin(el) * d, vc.z - Math.cos(az) * Math.cos(el) * d);
  cam.lookAt(vc);
  renderer.setViewport(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissor(v.x, H - v.y - v.h, v.w, v.h);
  renderer.setScissorTest(true);
  renderer.clear();
  renderer.render(scene, cam);
}
window.__done = true;
</script></body></html>`;

const tmp = join(here, '.render.html');
writeFileSync(tmp, html);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--allow-file-access-from-files'] });
try {
  const page = await browser.newPage({ viewport: { width: CW, height: CH } });
  page.on('pageerror', e => console.error('[pageerror]', e.message));
  await page.goto(pathToFileURL(tmp).href);
  await page.waitForFunction(() => window.__done === true, null, { timeout: 30000 });
  await page.locator('#c').screenshot({ path: output });
} finally {
  await browser.close();
  rmSync(tmp, { force: true });
}
console.log('render  ' + output);
