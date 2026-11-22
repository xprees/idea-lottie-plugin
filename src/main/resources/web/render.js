// Renders frames of one animation to PNG for the MCP tool lottie_render_frame. Driven like the preview page: the IDE injects
// window.__lottieBridge and calls window.lottiePreview.init(config); the frames come back as one message of PNG data URLs.
import { DotLottie } from './player/dotlottie-web.js';

function send(msg) {
  try {
    if (window.__lottieBridge) window.__lottieBridge(JSON.stringify(msg));
  } catch (e) {
    console.error(e);
  }
}

async function run(config) {
  const response = await fetch('../data/animation');
  const data = config.kind === 'json' ? await response.text() : await response.arrayBuffer();
  const canvas = document.createElement('canvas');
  canvas.width = config.width;
  canvas.height = config.height;
  let player;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out loading the animation')), 20000);
    player = new DotLottie({
      data,
      canvas,
      autoplay: false,
      loop: false,
      ...(config.animationId ? { animationId: config.animationId } : {}),
      ...(config.themeId ? { themeId: config.themeId } : {}),
      ...(config.background ? { backgroundColor: config.background } : {}),
      layout: { fit: 'contain', align: [0.5, 0.5] },
      renderConfig: { autoResize: false, devicePixelRatio: 1, freezeOnOffscreen: false },
    });
    player.addEventListener('load', () => { clearTimeout(timer); resolve(); });
    player.addEventListener('loadError', (e) => { clearTimeout(timer); reject(e.error || new Error('The animation could not be loaded')); });
  });
  try {
    const last = Math.max(0, player.totalFrames - 1);
    const images = [];
    for (const frame of config.frames) {
      const rendered = new Promise((resolve) => {
        const timer = setTimeout(resolve, 1500);
        const handler = () => { clearTimeout(timer); player.removeEventListener('render', handler); resolve(); };
        player.addEventListener('render', handler);
      });
      player.setFrame(Math.min(Math.max(0, frame), last));
      await rendered;
      images.push(canvas.toDataURL('image/png'));
    }
    send({ type: 'rendered', images });
  } finally {
    player.destroy();
  }
}

window.lottiePreview = {
  init(config) {
    DotLottie.setWasmUrl(config.wasmUrl);
    run(config).catch((e) => send({ type: 'renderError', message: String((e && e.message) || e) }));
  },
};
