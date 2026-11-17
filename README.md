# Lottie

<!-- Plugin description -->
Support for <b>Lottie</b> animations in IntelliJ IDEA, WebStorm, Rider and other JetBrains IDEs: Lottie
<code>.json</code> files and <code>.lottie</code> (dotLottie) archives get their own icon and open with a live preview.

<ul>
  <li><b>Lottie JSON</b> opens like SVG: the JSON editor next to a live <b>preview</b>, with <i>Editor</i>, <i>Editor and Preview</i>
      and <i>Preview</i> layouts. The preview follows your edits and keeps the last valid render while the JSON is broken</li>
  <li><b>dotLottie</b> (<code>.lottie</code>, versions 1 and 2) opens in the preview, with pickers for its animations, <b>themes</b>
      and <b>state machines</b>, and a panel listing its manifest and files</li>
  <li>A <b>timeline</b> to scrub through the animation, with playback controls (play/pause, frame stepping, loop, speed), markers
      and per-layer in/out bars</li>
  <li><b>Markers</b>, the named moments and segments your code plays or reacts to, are added, renamed, moved and deleted right on
      the timeline</li>
  <li><b>State machines</b> of interactive dotLottie files run in the preview: see the current state, drive the inputs, fire events,
      watch the event log, add and edit inputs, and edit the state machine as JSON with validation</li>
  <li>Playback controls: play/pause, loop, speed, <b>markers</b> (play one segment), zoom, and a checkerboard, light, dark or
      custom background</li>
  <li>The animation's <b>color palette</b> above the preview; in JSON files, <b>color swatches</b> in the gutter open the color picker
      and write the new color back as Lottie components</li>
  <li>JSON smarts: validation and completion from the official <b>Lottie JSON Schema</b>, a <b>Structure view</b> of layers, shapes,
      assets and markers, and <b>inlay hints</b> that name Lottie's codes (<code>"ty": 4</code> → Shape, in/out points in seconds, …)</li>
  <li>Code and preview in sync: the layer at the caret is outlined in the preview, and clicking the preview jumps to the layer
      under the mouse</li>
  <li><b>Extract Lottie JSON</b> from a <code>.lottie</code> file and <b>Pack as dotLottie</b> from a JSON file</li>
</ul>

Lottie <code>.json</code> files are recognized by their content (or a <code>*.lottie.json</code> name) and stay JSON files, so
everything the IDE does for JSON keeps working. Settings: <i>Settings › Languages &amp; Frameworks › Lottie</i>.
<!-- Plugin description end -->

Works in JetBrains IDEs based on the IntelliJ Platform **2026.1 and newer** (IntelliJ IDEA, WebStorm, Rider, PyCharm, …, and Android
Studio once it moves to that platform). The plugin is built and tested against the oldest supported IDE (`platformVersion` in
`gradle.properties`, which also gives its `since-build`) and verified against the latest IDEA, WebStorm and Rider releases in CI (the
full version matrix is opt-in: the `full-verify` pull request label or a manual run of the *Build* workflow). It declares no upper
build limit.

## Which files are Lottie

| File | Lottie when |
|---|---|
| `*.lottie` | always: a dotLottie archive (ZIP with `manifest.json` and animations under `a/`, or `animations/` in dotLottie 1.0) |
| `*.lottie.json` | always |
| `*.json` | the content looks like Lottie: the root object has at least five of the Lottie header keys `v`, `fr`, `ip`, `op`, `w`, `h`, `layers`, including `fr`, `ip` and `op`. Only the first 64 KB are read (exporters write these keys first), and the result is cached per file. Turn it off in the settings to use only the `.lottie.json` name |

Lottie JSON files keep the JSON file type: highlighting, formatting, folding and your own JSON schemas still apply. Unsaved edits
count, so a pasted animation becomes Lottie as soon as it looks like one (reopen the file to get the split editor).

## Features

| Area | What you get |
|---|---|
| Preview | Rendered by the official [dotLottie player](https://github.com/LottieFiles/dotlottie-web) (ThorVG, WebAssembly) in the IDE's embedded Chromium. The player is bundled: nothing is downloaded, and the preview page may not load anything from the network (images or fonts referenced by URL don't show) |
| Split editor | Lottie JSON opens as *Editor and Preview* (configurable); the layout chosen in a file is remembered for that file. Edits reach the preview 300 ms after you stop typing, keeping the current frame and playing state. Invalid JSON shows the parse error with its line and keeps the last valid render |
| dotLottie | Preview only (the file is binary). Toolbar pickers for the animation, theme (`t/`, applied through Lottie slots) and state machine (`s/`, interactive in the preview; URLs a state machine tries to open are blocked). A details panel lists the format version, generator, animations with their initial and scoped themes, themes, state machines and every file with its size |
| Playback | In the timeline header: first/previous/play-pause/next/last frame, loop and speed (0.25×–4×). In the toolbar: marker picker (plays the marker's segment; After Effects JSON marker names are shown by name), zoom to fit / actual size / in / out (also Ctrl/⌘ + wheel), background: checkerboard, light, dark or a custom color |
| Timeline | Under the preview: a frame ruler with the playhead, the markers (segments as bars, single-frame markers as flags) and, with *Show Layers in Timeline*, a lane per root layer with its in/out range. Press and drag anywhere to scrub (playback pauses while you scrub and resumes when you let go; Alt for fractional frames), hover for the frame and time, wheel over the ruler to step. With the timeline focused: ←/→ one frame, Shift+←/→ ten, Home/End, Space to play or pause. Double-click a marker to play only its segment; click a layer to select it (in JSON files the caret jumps to it and the preview outlines it) |
| State machines | A *State machine* tab next to the dotLottie details: the current state and player status, every input with its live value (a checkbox, a text field or *Fire*), reset to the initial value, and a log of transitions, input changes and custom events. Clicking or hovering the preview drives the machine's interactions. Inputs are added, edited and deleted from the panel (renaming updates every guard and action that uses the input); each change is one undoable edit of the archive. *Edit State Machine as JSON* opens `s/<id>.json` in a JSON editor with the bundled state machine schema and an inspection for unknown states, inputs, animations, markers, themes and layers; valid edits are written back to the archive. Each input and the custom events have a *Copy Code* button with the dotLottie web call |
| Markers | In JSON files markers are edited on the timeline: right-click the markers lane to add a marker at that frame or at the playhead, and a marker to rename it, move its start or end to the playhead or delete it; drag a marker's edge to change its range (a single-frame marker becomes a segment). *+* in the timeline header adds one at the playhead. Each edit is one undoable change to the `markers` array; marker names must be unique. In `.lottie` files markers are read-only (extract the JSON to edit them) |
| Palette | The distinct colors of the animation (fills, strokes, gradient stops, solid layers, text, color effects and the color slots they use) as swatches above the preview, with their uses in the tooltip. In JSON files a click walks the caret through the places the color is defined; the context menu copies it as hex or as a Lottie array |
| Color swatches | In JSON files, a gutter swatch next to every color value: `[r, g, b(, a)]` arrays of fills, strokes, text, color effects and slots, and the `"#rrggbb"` of solid layers. Clicking it opens the color picker; the new color is written back in the file's own form (0..1 components, or 0..255 for old exports; alpha kept when present). Positions, scales and other number triples get no swatch |
| Schema | Validation and completion from the [Lottie Animation Community](https://lottie.github.io/lottie-spec/) JSON Schema (bundled), applied automatically to Lottie files |
| Structure view | The composition (size, frame rate, duration), its layers by name with type, index, in/out points, parent, matte and hidden state, shapes and groups, assets (precompositions with their layers, images), markers and slots |
| Inlay hints | Layer and shape types (`"ty": 4` → Shape, `"ty": "fl"` → Fill), blend and matte modes, parent layer names, in/out/start points and keyframe times in seconds, and the layer's type and name in front of each layer object, visible when it is folded. Toggle in the settings or *Settings › Editor › Inlay Hints › Values › Lottie* |
| Sync | The root layer at the caret (or selected in the Structure view) is outlined in the preview, following it frame by frame; a caret in a keyframe shows its frame while paused; clicking the preview moves the caret to the topmost layer under the mouse |
| Actions | *Extract Lottie JSON* (`.lottie` context menu, editor tab menu, preview toolbar): writes the animation next to the archive with its images embedded as data URIs, and opens it. *Pack as dotLottie* (Lottie JSON): creates a dotLottie 2.0 file with the animation and the images it embeds or references next to it (stored under `i/`), and opens it. In Rider they are in the Solution Explorer menu |

## Markers as events for your code

Lottie has no separate event track: **markers** are how an animation names moments and segments for the code that plays it. A
marker is an entry in the root `markers` array, `{"cm": "hit", "tm": 15, "dr": 10}`: a name, a start frame and a duration (0 for a
single moment). Add them on the timeline, then refer to them by name in code, for example:

| Runtime | Play a marker's segment |
|---|---|
| dotLottie web (`@lottiefiles/dotlottie-web`) | `dotLottie.setMarker('hit'); dotLottie.play();` |
| lottie-web | `animation.goToAndPlay('hit', true);` |
| Lottie Android | `animationView.setMinAndMaxFrame("hit")` |
| Lottie Compose | `LottieClipSpec.Marker("hit")` as the `clipSpec` of `animateLottieCompositionAsState` |
| Lottie iOS | `animationView.play(fromMarker: "hit", toMarker: "outro")` |

To *react* when playback reaches a marker, compare the current frame with the marker's start in the player's frame callback (for
example dotLottie web's `frame` event, which carries `currentFrame`, together with `dotLottie.markers()`), or play the marker's segment
and wait for the `complete` event. The names you give markers are the names your code uses, so the timeline refuses duplicates.

## State machine inputs and events

Interactive dotLottie files (format 2.0) carry state machines in `s/`: states that play animations or segments, transitions guarded by
**inputs**, and actions that change inputs or fire **custom events**. Inputs are the API between the animation and your code: your code
sets them, and the animation reports back through events. The *State machine* tab runs the machine in the preview so you can try
both directions, and *Copy Code* gives the matching dotLottie web call, for example:

```js
dotLottie.stateMachineLoad('toggle');
dotLottie.stateMachineStart();
dotLottie.stateMachineSetBooleanInput('isOn', true);
dotLottie.stateMachineSetNumericInput('clicks', 3);
dotLottie.stateMachineFireEvent('reset');
dotLottie.addEventListener('stateMachineCustomEvent', (event) => {
  if (event.eventName === 'switched-on') { /* react */ }
});
```

## Settings

*Settings › Languages & Frameworks › Lottie*:

| Setting | Default |
|---|---|
| Recognize Lottie animations in `.json` files by their content | on |
| Lottie JSON files open as | Editor and Preview |
| Background | Checkerboard |
| Play animations when they open / Loop playback | on / on |
| Show color swatches in the gutter | on |
| Show inlay hints for Lottie codes | on |
| Sync the editor and the preview | on |

## Known limitations

- The preview needs JCEF. In an IDE runtime without it (some remote development clients) the preview shows a notice and the text
  editor keeps working.
- Images and fonts referenced by URL aren't loaded (the preview never accesses the network). Embedded images and images inside
  `.lottie` archives work.
- Gradient stops appear in the palette but have no gutter swatch: a gradient is one array holding every stop.
- Layer outlining and picking work on root layers of JSON files. A layer is found by rendering it alone, so a layer that is only
  visible through effects of other layers may not be found.
- State machines are edited as JSON (with schema and checks) and through their inputs; there is no graphical editor of states and
  transitions.
- The palette is read-only for now. Recoloring the animation (for example as a dotLottie theme) is planned.

## How it works

The file types and editors are thin: Lottie JSON keeps the JSON language, and a `FileEditorProvider` wraps the platform's text editor
in a `TextEditorWithPreview` when the sniffer (`detect/LottieSniffer`) recognizes the file. The preview is a JCEF page
(`src/main/resources/web/`) served from a private origin (`https://lottie-preview.invalid/`) that the plugin answers itself: the page,
the player and the animation bytes. The page runs `@lottiefiles/dotlottie-web` and talks to the IDE through a `JBCefJSQuery` bridge.
Layer outlining and picking render one layer alone (the other layers made transparent) on an offscreen canvas and read its pixels.

The model code (`json/`, `detect/`, `dotlottie/`, `palette/`, `model/`) has no IntelliJ dependencies: a small JSON reader/writer, the
dotLottie archive reader and writer, and the color scanner shared by the palette and the gutter swatches.

## Building

```shell
./gradlew buildPlugin     # build/distributions/lottie-<version>.zip
./gradlew runIde          # a sandbox IDE with the plugin
./gradlew test
./gradlew verifyPlugin    # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

The build downloads the pinned player (`@lottiefiles/dotlottie-web`) and schema (`@lottie-animation-community/lottie-specs`) from
the npm registry and checks them against the integrity hashes in `gradle.properties`; no binaries are committed. To update one, change
its version and integrity there (`npm view <package>@<version> dist.integrity`), and update `THIRD_PARTY_NOTICES.md` and the changelog.

### Versions and releases

The plugin version is `major.minor.patch`, derived from git tags:

- **Tagged commit:** the tag is the version (`0.1.3`, `v0.1.3`, or `0.2.0-beta.1`, whose suffix selects the Marketplace channel: no suffix = stable, `-beta.N` = beta).
- **Any other build:** `major.minor` come from `pluginVersionMajor` / `pluginVersionMinor` in `gradle.properties` (the `PLUGIN_VERSION_MAJOR` / `PLUGIN_VERSION_MINOR` environment variables override them; fallback `0.1`), and the patch is the next free one: the highest existing `major.minor.N` tag plus one, or `0` if there is none.
- `-PpluginVersion=x.y.z` overrides everything.

**On every merge to `master`**, once build, tests and plugin verification are green, the *Build* workflow tags the merge commit with the next version and creates a draft GitHub release for that tag. Major and minor come from the repository variables `PLUGIN_VERSION_MAJOR` and `PLUGIN_VERSION_MINOR` (*Settings → Secrets and variables → Actions → Variables*); if they are not defined, the values in `gradle.properties` are used. The patch increases automatically.

The draft release already has the built plugin attached: `lottie-<version>.zip`, plus `lottie-<version>-signed.zip` when the signing secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are configured. That zip is also what you upload by hand for the very first Marketplace release.

To start a new line (say `0.2`), change the two variables; the next merge is tagged `0.2.0`. To ship a build, publish its draft release: the *Release* workflow then builds that exact tag, signs it and publishes it to the Marketplace (`PUBLISH_TOKEN` secret). Signing and publishing are refused for builds that aren't from a tag. For a beta, edit the draft's tag before publishing (e.g. `0.2.0-beta.1`).

## Contributing

Bug reports, fixes and features are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) explains how to set up the project, the coding
and testing guidelines, and how pull requests are reviewed. Please follow the [Code of Conduct](CODE_OF_CONDUCT.md), and report
security problems privately as described in [SECURITY.md](SECURITY.md).

## License

Apache-2.0, see [LICENSE](LICENSE). The bundled player and schema are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
"Lottie" is used here only to name the file format; the plugin is not affiliated with Airbnb, LottieFiles or the Lottie Animation
Community. The plugin and file icons are original artwork made for this plugin and licensed with it under Apache-2.0; they are not
the Lottie, LottieFiles or dotLottie logos.
