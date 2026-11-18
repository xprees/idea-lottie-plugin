// Lottie preview page. Driven by the IDE through window.lottiePreview; reports back through window.__lottieBridge(json),
// which the IDE injects before calling init(). Everything is loaded from the plugin's own origin.
import { DotLottie } from './player/dotlottie-web.js';

const stage = document.getElementById('stage');
const frameBox = document.getElementById('frame');
const canvas = document.getElementById('player');
const overlay = document.getElementById('overlay');
const message = document.getElementById('message');

/** Longest side of the offscreen canvas used to isolate layers for highlighting and picking. */
const PROBE_SIZE = 512;

const state = {
  player: null,
  probe: null,
  probeCanvas: null,
  probeLayer: null,
  probeScale: 1,
  /** Lottie JSON text of the current animation (JSON files only), for layer isolation. */
  json: null,
  parsed: null,
  width: 0,
  height: 0,
  zoomMode: 'fit',
  zoom: 1,
  lastFrameSent: 0,
  highlight: null,
  /** The palette color highlight: { mode: 'outline' | 'dim' } while a swatch is hovered, else null. */
  colorHighlight: null,
  pickEnabled: false,
  loadToken: 0,
  /** Id of the running dotLottie state machine. */
  stateMachineId: null,
  /** The state machine is chosen but not started yet: it starts with Play (autoplay is off). */
  stateMachinePending: false,
  speed: 1,
  loop: true,
  autoplayNext: true,
};

function send(msg) {
  try {
    if (window.__lottieBridge) window.__lottieBridge(JSON.stringify(msg));
  } catch (e) {
    console.error(e);
  }
}

// The canvas has the keyboard focus after a click, so the IDE never sees these keys: hand the playback keys (Space, arrows,
// Home/End, L, with their modifiers) to the plugin, which maps them like the rest of the preview (PlaybackKeys.kt).
const PLAYBACK_KEYS = new Set([' ', 'ArrowLeft', 'ArrowRight', 'Home', 'End', 'l', 'L']);
document.addEventListener('keydown', (e) => {
  if (!PLAYBACK_KEYS.has(e.key) || e.isComposing) return;
  e.preventDefault();
  send({ type: 'key', key: e.key, shift: e.shiftKey, ctrl: e.ctrlKey, meta: e.metaKey, alt: e.altKey });
});

function showMessage(text) {
  message.textContent = text || '';
  message.hidden = !text;
}

function once(player, type, timeoutMs = 15000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      player.removeEventListener(type, onEvent);
      reject(new Error(`Timed out waiting for ${type}`));
    }, timeoutMs);
    const onEvent = (event) => {
      clearTimeout(timer);
      player.removeEventListener(type, onEvent);
      resolve(event);
    };
    player.addEventListener(type, onEvent);
  });
}

function sendFrame(force) {
  const p = state.player;
  if (!p || !p.isLoaded) return;
  const now = performance.now();
  if (!force && now - state.lastFrameSent < 40) return;
  state.lastFrameSent = now;
  send({ type: 'frame', frame: p.currentFrame, playing: p.isPlaying });
}

function applyZoom() {
  if (!state.width || !state.height) return;
  if (state.zoomMode === 'fit') {
    const availW = Math.max(16, stage.clientWidth - 32);
    const availH = Math.max(16, stage.clientHeight - 32);
    state.zoom = Math.max(0.01, Math.min(availW / state.width, availH / state.height));
  }
  const w = Math.max(1, Math.round(state.width * state.zoom));
  const h = Math.max(1, Math.round(state.height * state.zoom));
  for (const c of [canvas, overlay]) {
    c.style.width = `${w}px`;
    c.style.height = `${h}px`;
  }
  const dpr = window.devicePixelRatio || 1;
  overlay.width = Math.round(w * dpr);
  overlay.height = Math.round(h * dpr);
  if (state.player) state.player.resize();
  drawHighlight();
  send({ type: 'zoom', zoom: state.zoom, mode: state.zoomMode });
}

new ResizeObserver(() => {
  if (state.zoomMode === 'fit') applyZoom();
}).observe(stage);

function attachEvents(player) {
  player.addEventListener('frame', () => {
    sendFrame(false);
    if (state.colorHighlight) updateColorFrame();
    else if (state.highlight != null) updateHighlightFrame();
  });
  player.addEventListener('play', () => send({ type: 'playing', playing: true }));
  player.addEventListener('pause', () => { send({ type: 'playing', playing: false }); sendFrame(true); });
  player.addEventListener('stop', () => { send({ type: 'playing', playing: false }); sendFrame(true); });
  player.addEventListener('complete', () => { send({ type: 'playing', playing: false }); sendFrame(true); });
  player.addEventListener('loadError', (e) => send({ type: 'error', message: String(e.error?.message || e.error || 'The animation could not be loaded') }));
  player.addEventListener('renderError', (e) => send({ type: 'error', message: String(e.error?.message || e.error || 'The animation could not be rendered') }));
  // State machine events go to the IDE's log as they happen, followed by a fresh snapshot (state, status, input values)
  const smEvents = {
    stateMachineStart: () => ({}),
    stateMachineStop: () => ({}),
    stateMachineTransition: (e) => ({ from: e.fromState, to: e.toState }),
    stateMachineStateEntered: (e) => ({ state: e.state }),
    stateMachineStateExit: (e) => ({ state: e.state }),
    stateMachineCustomEvent: (e) => ({ name: e.eventName }),
    stateMachineError: (e) => ({ error: String(e.error ?? '') }),
    stateMachineBooleanInputValueChange: (e) => ({ input: e.inputName, from: e.oldValue, to: e.newValue }),
    stateMachineNumericInputValueChange: (e) => ({ input: e.inputName, from: e.oldValue, to: e.newValue }),
    stateMachineStringInputValueChange: (e) => ({ input: e.inputName, from: e.oldValue, to: e.newValue }),
    stateMachineInputFired: (e) => ({ input: e.inputName }),
  };
  for (const [type, fields] of Object.entries(smEvents)) {
    player.addEventListener(type, (e) => {
      send({ type: 'smEvent', event: type.replace(/^stateMachine/, ''), ...fields(e) });
      scheduleSmSnapshot();
    });
  }
}

/** After Effects markers exported by Bodymovin carry their name as JSON (`{"name":"intro"}`). */
function markerLabel(name) {
  if (typeof name === 'string' && name.trim().startsWith('{')) {
    try {
      const parsed = JSON.parse(name);
      if (parsed && typeof parsed.name === 'string') return parsed.name;
    } catch (e) { /* plain name */ }
  }
  return name;
}

let smSnapshotPending = false;

/** One snapshot after a burst of state machine events (a transition fires several). */
function scheduleSmSnapshot() {
  if (smSnapshotPending) return;
  smSnapshotPending = true;
  setTimeout(() => {
    smSnapshotPending = false;
    send(smSnapshot());
  }, 0);
}

/** The running state machine: id, status, current state and the inputs with their current values. */
function smSnapshot() {
  const p = state.player;
  const running = !!(p && p.isStateMachineRunning);
  const snapshot = { type: 'smState', id: state.stateMachineId, running, status: '', state: '', inputs: [] };
  if (!p || !running) return snapshot;
  snapshot.status = p.stateMachineGetStatus();
  snapshot.state = p.stateMachineGetCurrentState();
  const list = p.stateMachineGetInputs() || [];
  for (let i = 0; i + 1 < list.length; i += 2) {
    const name = list[i], type = list[i + 1];
    if (name.startsWith('@')) continue; // the player's own inputs, such as @elapsedTime
    let value = null;
    if (type === 'Boolean') value = p.stateMachineGetBooleanInput(name);
    else if (type === 'Numeric') value = p.stateMachineGetNumericInput(name);
    else if (type === 'String') value = p.stateMachineGetStringInput(name);
    snapshot.inputs.push({ name, type, value: value ?? null });
  }
  return snapshot;
}

function meta() {
  const p = state.player;
  const size = p.animationSize ? p.animationSize() : { width: 0, height: 0 };
  return {
    type: 'loaded',
    width: size.width,
    height: size.height,
    totalFrames: p.totalFrames,
    duration: p.duration,
    markers: (p.markers ? p.markers() : []).map((m) => ({ name: m.name, label: markerLabel(m.name), start: m.start, end: m.end })),
    activeAnimationId: p.activeAnimationId ?? null,
    activeThemeId: p.activeThemeId ?? null,
    playing: p.isPlaying,
    frame: p.currentFrame,
  };
}

const api = {
  /** Called once by the IDE after the bridge is injected. */
  init(config) {
    DotLottie.setWasmUrl(config.wasmUrl);
    if (config.colors) {
      const root = document.documentElement.style;
      for (const [k, v] of Object.entries(config.colors)) root.setProperty(`--${k}`, v);
    }
    api.setBackground(config.background || 'checkerboard', config.customBackground);
    state.loop = config.loop !== false;
    state.autoplayNext = config.autoplay !== false;
    state.player = new DotLottie({
      canvas,
      autoplay: false,
      loop: state.loop,
      layout: { fit: 'contain', align: [0.5, 0.5] },
      renderConfig: { autoResize: true, freezeOnOffscreen: false },
      stateMachineConfig: { openUrlPolicy: { requireUserInteraction: true, whitelist: [] } },
    });
    attachEvents(state.player);
    // The IDE starts loading as soon as it hears 'ready'; a load() before the player's WebAssembly is initialized would be dropped
    const announce = () => send({ type: 'ready' });
    if (state.player.isReady) announce();
    else state.player.addEventListener('ready', announce);
  },

  /**
   * Loads (or reloads) the animation at `url`. `kind` is 'json' or 'lottie'. A reload keeps the frame and the playing state,
   * so editing the JSON doesn't restart the animation.
   */
  async load(options) {
    const p = state.player;
    if (!p) return;
    const token = ++state.loadToken;
    const keep = options.keepState && p.isLoaded;
    const frame = keep ? p.currentFrame : 0;
    const wasPlaying = keep ? p.isPlaying : state.autoplayNext;
    try {
      const response = await fetch(options.url);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = options.kind === 'json' ? await response.text() : await response.arrayBuffer();
      if (token !== state.loadToken) return;
      state.json = options.kind === 'json' ? data : null;
      state.parsed = null;
      state.probeLayer = null;
      const loaded = once(p, 'load');
      const failed = once(p, 'loadError').then((e) => { throw new Error(String(e.error?.message || e.error || 'The animation could not be loaded')); });
      p.load({
        data,
        autoplay: false,
        loop: state.loop,
        speed: state.speed,
        animationId: options.animationId || undefined,
        themeId: options.themeId || undefined,
        layout: { fit: 'contain', align: [0.5, 0.5] },
      });
      await Promise.race([loaded, failed]);
      if (token !== state.loadToken) return;
      const size = p.animationSize ? p.animationSize() : { width: canvas.width, height: canvas.height };
      state.width = size.width || 512;
      state.height = size.height || 512;
      showMessage('');
      applyZoom();
      if (options.marker) p.setMarker(options.marker);
      if (keep && frame > 0) p.setFrame(Math.min(frame, Math.max(0, p.totalFrames - 1)));
      // autoplay is off: the state machine is chosen but waits for Play, as running it plays the animation (a reload keeps it waiting)
      const waiting = keep ? state.stateMachinePending : !state.autoplayNext;
      if (options.stateMachineId && waiting) {
        state.stateMachineId = options.stateMachineId;
        state.stateMachinePending = true;
        send(smSnapshot());
      } else if (options.stateMachineId) {
        // a reload (the file changed) starts the state machine again, with the new definition
        api.setStateMachine(options.stateMachineId);
      } else if (wasPlaying) {
        p.play();
      }
      send(meta());
      sendFrame(true);
      if (state.highlight != null) api.highlightLayer(state.highlight);
    } catch (e) {
      if (token !== state.loadToken) return;
      showMessage(String(e.message || e));
      send({ type: 'error', message: String(e.message || e) });
    }
  },

  play() {
    const p = state.player;
    if (!p) return;
    if (state.stateMachinePending) {
      api.setStateMachine(state.stateMachineId);
      return;
    }
    if (!p.loop && p.totalFrames > 0 && p.currentFrame >= p.totalFrames - 1) p.setFrame(0);
    p.play();
  },
  pause() { state.player?.pause(); },
  setLoop(loop) { state.loop = loop; state.player?.setLoop(loop); },
  setSpeed(speed) { state.speed = speed; state.player?.setSpeed(speed); },
  seek(frame) {
    const p = state.player;
    if (!p) return;
    p.pause();
    p.setFrame(frame);
    sendFrame(true);
    if (state.colorHighlight) updateColorFrame();
    else if (state.highlight != null) updateHighlightFrame();
  },
  setMarker(name) {
    const p = state.player;
    if (!p) return;
    if (name) p.setMarker(name); else p.resetSegment();
    send(meta());
  },
  setBackground(kind, color) {
    stage.className = '';
    stage.style.background = '';
    if (kind === 'custom') stage.style.background = color || '#808080';
    else stage.classList.add(`bg-${kind}`);
  },
  /** mode 'fit' (follow the panel size) or 'fixed' with an explicit zoom factor. */
  setZoom(mode, zoom) {
    state.zoomMode = mode;
    if (mode !== 'fit') state.zoom = Math.max(0.05, Math.min(32, zoom));
    applyZoom();
  },
  selectAnimation(id) {
    const p = state.player;
    if (!p) return;
    p.loadAnimation(id);
    once(p, 'load').then(() => {
      const size = p.animationSize();
      state.width = size.width;
      state.height = size.height;
      applyZoom();
      if (state.autoplayNext) p.play();
      send(meta());
    }).catch(() => {});
  },
  setTheme(id) {
    const p = state.player;
    if (!p) return;
    if (id) p.setTheme(id); else p.resetTheme();
    send(meta());
  },
  setStateMachine(id) {
    const p = state.player;
    if (!p) return;
    if (p.isStateMachineRunning) p.stateMachineStop();
    state.stateMachineId = id || null;
    state.stateMachinePending = false;
    if (id) {
      if (!p.stateMachineLoad(id)) send({ type: 'smEvent', event: 'Error', error: `The state machine '${id}' could not be loaded` });
      else if (!p.stateMachineStart()) send({ type: 'smEvent', event: 'Error', error: `The state machine '${id}' could not be started` });
    }
    send(smSnapshot());
  },
  smSetInput(name, type, value) {
    const p = state.player;
    if (!p?.isStateMachineRunning) return;
    if (type === 'Boolean') p.stateMachineSetBooleanInput(name, !!value);
    else if (type === 'Numeric') p.stateMachineSetNumericInput(name, Number(value));
    else if (type === 'String') p.stateMachineSetStringInput(name, String(value));
    scheduleSmSnapshot();
  },
  smFire(name) {
    if (state.player?.isStateMachineRunning) state.player.stateMachineFireEvent(name);
    scheduleSmSnapshot();
  },
  smReset(name) {
    if (state.player?.isStateMachineRunning) state.player.stateMachineResetInput(name);
    scheduleSmSnapshot();
  },
  smRestart() {
    api.setStateMachine(state.stateMachineId);
  },
  setPickEnabled(enabled) {
    state.pickEnabled = enabled;
    frameBox.classList.toggle('pickable', enabled && state.json != null);
  },
  /**
   * Shows where a palette color is used: `url` serves a copy of the animation with that color white and every other color black
   * (images hidden), which the probe renders frame by frame. 'outline' draws the white area's edge, 'dim' veils everything else.
   * A null url ends it, and any layer highlight comes back.
   */
  async highlightColor(url, mode) {
    const token = ++colorToken;
    if (!url) {
      state.colorHighlight = null;
      colorMask = null;
      state.probeLayer = null;
      if (state.highlight != null && state.json != null) api.highlightLayer(state.highlight); else clearOverlay();
      return;
    }
    try {
      const data = await (await fetch(url)).json();
      if (token !== colorToken) return;
      state.probeLayer = null;
      await loadProbe(data);
      if (token !== colorToken) return;
      state.colorHighlight = { mode: mode === 'dim' ? 'dim' : 'outline' };
      await updateColorFrame();
    } catch (e) {
      if (token === colorToken) state.colorHighlight = null;
    }
  },
  /** Outlines root layer `index` (0 = topmost) at the current frame; null clears the outline. */
  highlightLayer(index) {
    state.highlight = index;
    if (index == null || state.json == null) {
      state.highlight = null;
      clearOverlay();
      return;
    }
    isolate(index).then(() => updateHighlightFrame()).catch(() => clearOverlay());
  },
};

window.lottiePreview = api;

// ---- Layer isolation: render one root layer alone on an offscreen canvas and read its pixels. -------------------------------

function parsedJson() {
  if (state.parsed == null && state.json != null) {
    try {
      state.parsed = JSON.parse(state.json);
    } catch (e) {
      state.parsed = null;
    }
  }
  return state.parsed;
}

/** Loads `data` into the offscreen probe, creating (or resizing) it first when needed. */
async function loadProbe(data) {
  const scale = Math.min(1, PROBE_SIZE / Math.max(state.width, state.height, 1));
  const w = Math.max(1, Math.round(state.width * scale));
  const h = Math.max(1, Math.round(state.height * scale));
  state.probeScale = scale;
  const options = { data, autoplay: false, loop: false, layout: { fit: 'contain', align: [0.5, 0.5] } };
  if (state.probe && state.probeCanvas.width === w && state.probeCanvas.height === h) {
    const loaded = once(state.probe, 'load');
    state.probe.load(options);
    await loaded;
    return state.probe;
  }
  if (state.probe) state.probe.destroy();
  state.probeCanvas = document.createElement('canvas');
  state.probeCanvas.width = w;
  state.probeCanvas.height = h;
  // A new instance takes its first animation from the constructor: load() before it is ready would be dropped
  let probe;
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timed out loading the layer probe')), 15000);
    probe = new DotLottie({
      ...options,
      canvas: state.probeCanvas,
      renderConfig: { autoResize: false, devicePixelRatio: 1, freezeOnOffscreen: false },
    });
    probe.addEventListener('load', () => { clearTimeout(timer); resolve(); });
    probe.addEventListener('loadError', (e) => { clearTimeout(timer); reject(e.error || new Error('Layer probe failed')); });
  });
  state.probe = probe;
  return probe;
}

/** Loads the animation into the probe with every root layer transparent except `index` (and the track matte it uses). */
async function isolate(index) {
  const json = parsedJson();
  if (!json || !Array.isArray(json.layers) || index < 0 || index >= json.layers.length) throw new Error('No such layer');
  if (state.probe && state.probeLayer === index) return state.probe;
  const keep = new Set([index]);
  const target = json.layers[index];
  if (target && target.tt && index > 0) keep.add(index - 1);
  // Other layers get opacity 0 rather than `hd`: parenting still applies (children inherit transforms, not opacity), and the
  // renderer hangs on some hidden layers that use slots.
  const transparent = { a: 0, k: 0 };
  const copy = { ...json, layers: json.layers.map((layer, i) => (keep.has(i) ? layer : { ...layer, ks: { ...(layer.ks || {}), o: transparent } })) };
  state.probeLayer = null;
  const probe = await loadProbe(copy);
  state.probeLayer = index;
  return probe;
}

async function renderProbe(frame) {
  const probe = state.probe;
  const rendered = once(probe, 'render', 1000).catch(() => {});
  probe.setFrame(frame);
  await rendered;
  const ctx = state.probeCanvas.getContext('2d', { willReadFrequently: true });
  return ctx ? ctx.getImageData(0, 0, state.probeCanvas.width, state.probeCanvas.height) : null;
}

function bounds(image) {
  const { width, height, data } = image;
  let minX = width, minY = height, maxX = -1, maxY = -1;
  for (let y = 0; y < height; y++) {
    const row = y * width * 4;
    for (let x = 0; x < width; x++) {
      if (data[row + x * 4 + 3] > 8) {
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
  }
  return maxX < 0 ? null : { x: minX, y: minY, w: maxX - minX + 1, h: maxY - minY + 1 };
}

let highlightBusy = false;
let highlightBox = null;

async function updateHighlightFrame() {
  if (highlightBusy || state.highlight == null || !state.probe || state.probeLayer !== state.highlight) return;
  highlightBusy = true;
  try {
    const image = await renderProbe(state.player.currentFrame);
    highlightBox = image ? bounds(image) : null;
    drawHighlight();
  } finally {
    highlightBusy = false;
  }
}

// ---- Palette color highlight: the probe renders the mask animation (the color white, the rest black) ------------------------

let colorToken = 0;
let colorBusy = false;
/** The last mask: a canvas the probe's size, the color's area tinted and its edge drawn in the highlight color. */
let colorMask = null;

async function updateColorFrame() {
  if (colorBusy || !state.colorHighlight || !state.probe) return;
  colorBusy = true;
  try {
    const token = colorToken;
    const image = await renderProbe(state.player.currentFrame);
    if (!image || token !== colorToken || !state.colorHighlight) return;
    colorMask = maskCanvas(image, state.colorHighlight.mode);
    drawColorHighlight();
  } finally {
    colorBusy = false;
  }
}

function maskCanvas(image, mode) {
  const { width, height, data } = image;
  const inside = new Uint8Array(width * height);
  for (let i = 0; i < width * height; i++) {
    const a = data[i * 4 + 3];
    // white and opaque enough: the color shows there (anti-aliased edges and translucent overlaps count from half)
    if (a > 32 && data[i * 4] > 127) inside[i] = 1;
  }
  const out = new ImageData(width, height);
  const highlight = hexColor(getComputedStyle(document.documentElement).getPropertyValue('--highlight').trim() || '#3574f0');
  const veil = hexColor(getComputedStyle(document.documentElement).getPropertyValue('--checker-a').trim() || '#ffffff');
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = y * width + x;
      const o = i * 4;
      if (inside[i]) {
        const edge = x === 0 || y === 0 || x === width - 1 || y === height - 1 ||
          !inside[i - 1] || !inside[i + 1] || !inside[i - width] || !inside[i + width];
        if (edge) {
          out.data.set([highlight[0], highlight[1], highlight[2], 255], o);
        } else if (mode === 'outline') {
          out.data.set([highlight[0], highlight[1], highlight[2], 40], o);
        }
      } else if (mode === 'dim') {
        out.data.set([veil[0], veil[1], veil[2], 200], o);
      }
    }
  }
  const c = document.createElement('canvas');
  c.width = width;
  c.height = height;
  c.getContext('2d').putImageData(out, 0, 0);
  return c;
}

function hexColor(hex) {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h.slice(0, 6), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

function drawColorHighlight() {
  const ctx = overlay.getContext('2d');
  ctx.clearRect(0, 0, overlay.width, overlay.height);
  if (!colorMask || !state.colorHighlight) return;
  ctx.save();
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(colorMask, 0, 0, overlay.width, overlay.height);
  ctx.restore();
}

function clearOverlay() {
  highlightBox = null;
  const ctx = overlay.getContext('2d');
  ctx.clearRect(0, 0, overlay.width, overlay.height);
}

function drawHighlight() {
  if (state.colorHighlight) return drawColorHighlight();
  const ctx = overlay.getContext('2d');
  ctx.clearRect(0, 0, overlay.width, overlay.height);
  if (!highlightBox || state.highlight == null) return;
  const k = (overlay.width / (state.width || 1)) / state.probeScale;
  const color = getComputedStyle(document.documentElement).getPropertyValue('--highlight').trim() || '#3574f0';
  ctx.save();
  ctx.lineWidth = Math.max(1, window.devicePixelRatio || 1);
  ctx.strokeStyle = color;
  ctx.setLineDash([6 * ctx.lineWidth, 4 * ctx.lineWidth]);
  ctx.fillStyle = color + '22';
  const x = highlightBox.x * k, y = highlightBox.y * k, w = highlightBox.w * k, h = highlightBox.h * k;
  ctx.fillRect(x, y, w, h);
  ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
  ctx.restore();
}

/** Finds the topmost visible root layer under a click and tells the IDE, which moves the caret there. */
canvas.addEventListener('click', async (event) => {
  if (!state.pickEnabled || state.json == null || state.player?.isStateMachineRunning) return;
  const json = parsedJson();
  if (!json || !Array.isArray(json.layers)) return;
  const rect = canvas.getBoundingClientRect();
  const ax = (event.clientX - rect.left) / rect.width * state.width;
  const ay = (event.clientY - rect.top) / rect.height * state.height;
  const frame = state.player.currentFrame;
  const previous = state.highlight;
  document.body.style.cursor = 'progress';
  try {
    for (let i = 0; i < json.layers.length; i++) {
      const layer = json.layers[i];
      if (!layer || layer.hd || layer.td || layer.ty === 3 || layer.ty === 13 || layer.ty === 6) continue;
      const ip = layer.ip ?? -Infinity, op = layer.op ?? Infinity;
      const absolute = frame + (json.ip || 0);
      if (absolute < ip || absolute >= op) continue;
      await isolate(i);
      const image = await renderProbe(frame);
      if (!image) continue;
      const px = Math.floor(ax * state.probeScale), py = Math.floor(ay * state.probeScale);
      let hit = false;
      for (let dy = -2; dy <= 2 && !hit; dy++) {
        for (let dx = -2; dx <= 2 && !hit; dx++) {
          const x = px + dx, y = py + dy;
          if (x < 0 || y < 0 || x >= image.width || y >= image.height) continue;
          if (image.data[(y * image.width + x) * 4 + 3] > 8) hit = true;
        }
      }
      if (hit) {
        send({ type: 'pick', index: i });
        state.highlight = i;
        highlightBox = bounds(image);
        drawHighlight();
        return;
      }
    }
    send({ type: 'pick', index: null });
    state.highlight = previous;
    if (previous != null) api.highlightLayer(previous); else clearOverlay();
  } finally {
    document.body.style.cursor = '';
  }
});

// Ctrl/Cmd + wheel zooms, like the IDE's image viewer
stage.addEventListener('wheel', (event) => {
  if (!(event.ctrlKey || event.metaKey)) return;
  event.preventDefault();
  const factor = event.deltaY < 0 ? 1.1 : 1 / 1.1;
  api.setZoom('fixed', state.zoom * factor);
}, { passive: false });
