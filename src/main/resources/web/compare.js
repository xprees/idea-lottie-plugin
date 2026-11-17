// The optimizer's visual check: renders the same frames of the original (data/a) and the optimized file (data/b) with the
// preview's player and compares the pixels. Driven like the preview page: the IDE injects window.__lottieBridge and calls
// window.lottiePreview.init(config); the result is sent back as one message.
import { DotLottie } from './player/dotlottie-web.js';

/** A pixel counts as different when a channel differs by more than this (out of 255). */
const PIXEL_THRESHOLD = 24;

function send(msg) {
  try {
    if (window.__lottieBridge) window.__lottieBridge(JSON.stringify(msg));
  } catch (e) {
    console.error(e);
  }
}

function once(target, type, timeout) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${type}`)), timeout);
    const handler = (e) => {
      clearTimeout(timer);
      target.removeEventListener(type, handler);
      resolve(e);
    };
    target.addEventListener(type, handler);
  });
}

async function createPlayer(data, width, height, animationId) {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  let player;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out loading the animation')), 20000);
    player = new DotLottie({
      data,
      canvas,
      autoplay: false,
      loop: false,
      ...(animationId ? { animationId } : {}),
      layout: { fit: 'contain', align: [0.5, 0.5] },
      renderConfig: { autoResize: false, devicePixelRatio: 1, freezeOnOffscreen: false },
    });
    player.addEventListener('load', () => { clearTimeout(timer); resolve(); });
    player.addEventListener('loadError', (e) => { clearTimeout(timer); reject(e.error || new Error('The animation could not be loaded')); });
  });
  return { player, canvas };
}

async function render(p, frame) {
  const rendered = once(p.player, 'render', 1500).catch(() => {});
  p.player.setFrame(frame);
  await rendered;
  const ctx = p.canvas.getContext('2d', { willReadFrequently: true });
  return ctx.getImageData(0, 0, p.canvas.width, p.canvas.height);
}

/** The share of pixels (0..1) that differ by more than PIXEL_THRESHOLD in any channel. */
function difference(a, b) {
  const da = a.data, db = b.data;
  let differing = 0;
  for (let i = 0; i < da.length; i += 4) {
    if (Math.abs(da[i] - db[i]) > PIXEL_THRESHOLD || Math.abs(da[i + 1] - db[i + 1]) > PIXEL_THRESHOLD ||
        Math.abs(da[i + 2] - db[i + 2]) > PIXEL_THRESHOLD || Math.abs(da[i + 3] - db[i + 3]) > PIXEL_THRESHOLD) differing++;
  }
  return differing / (da.length / 4);
}

async function data(name, kind) {
  const response = await fetch(`../data/${name}`);
  return kind === 'json' ? response.text() : response.arrayBuffer();
}

async function run(config) {
  const [original, optimized] = await Promise.all([data('a', config.kind), data('b', config.kind)]);
  const animations = config.animations && config.animations.length ? config.animations : [null];
  let frames = 0;
  let worst = null;
  for (const id of animations) {
    const a = await createPlayer(original, config.width, config.height, id);
    const b = await createPlayer(optimized, config.width, config.height, id);
    try {
      const total = Math.max(1, Math.floor(a.player.totalFrames));
      const count = Math.min(config.frames, total);
      for (let i = 0; i < count; i++) {
        const frame = count === 1 ? 0 : Math.round(i * (total - 1) / (count - 1));
        const share = difference(await render(a, frame), await render(b, frame));
        frames++;
        if (!worst || share > worst.share) {
          worst = { share, frame, animation: id, original: a.canvas.toDataURL('image/png'), optimized: b.canvas.toDataURL('image/png') };
        }
      }
    } finally {
      a.player.destroy();
      b.player.destroy();
    }
  }
  send({ type: 'compare', frames, worst });
}

window.lottiePreview = {
  init(config) {
    DotLottie.setWasmUrl(config.wasmUrl);
    run(config).catch((e) => send({ type: 'compareError', message: String((e && e.message) || e) }));
  },
};
