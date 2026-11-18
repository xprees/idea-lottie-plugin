# Changelog

## [Unreleased]

## [2026.3.8] - 2026-11-18

### Added

- The *Code* tab writes whole components for the web players, typed in TypeScript (JavaScript when the project has no
  `tsconfig.json`, or picked next to the platform): the player instance ready to use (Vue: `useTemplateRef<DotLottieVueInstance>`
  and a reactive `dotLottie` ref), a typed `playMarker` and the marker playback is in, kept in reactive state with a `switch` over
  the marker names. Android, Compose and Flutter get code that follows the markers as well
- *Copy prompt for an AI agent* in the *Code* tab: the file, player, markers, state machine and generated component as a prompt for
  a coding agent to integrate the animation
- *→ dotLottie 2* after the version of a dotLottie 1.0 file in the preview toolbar converts it; *Convert to dotLottie 2.0* converts
  several files selected in the Project view at once
- The dotLottie 1.0 banner can be closed, or turned off (also in the settings)
- Playback buttons show their key in the tooltip (Space, ←/→, Home/End, L)

### Changed

- Animations no longer play when they open (*Play animations when they open* is off by default); a state machine that loads first
  starts with Play
- A playing preview pauses when its editor tab is hidden or the IDE window loses the focus (setting)
- Play button in the color of the other playback controls
- Code snippets are highlighted in their own language (a Vue component as Vue, the calls in it as TypeScript, ...)

### Fixed

- The dotLottie 1.0 banner showed only over the first `.lottie` file opened

## [2026.3.7] - 2026-11-18

### Fixed

- No use of deprecated platform API (`ReadAction.compute`), which the JetBrains Marketplace verifier reported
- Fewer reasons for the IDE to ask for a restart when the plugin is disabled, updated or uninstalled: undo history of `.lottie`
  edits, cached file information and open state machine editors no longer keep the plugin loaded (state machine edits still
  waiting are written first)

## [2026.3.4] - 2026-11-18

### Added

- More platforms in the *Code* tab: dotLottie Vue, Svelte, Solid, Web Component, React Native and Flutter, also guessed from the
  project's dependencies
- Markers of `.lottie` files are edited on the timeline like those of JSON files (add, rename, move, delete), each edit undoable
- Right-clicking a layer row of the timeline offers adding a marker, like the markers lane
- *L* turns looping on or off anywhere in the preview
- Hovering a palette color outlines where it is used in the preview; *Dim the other colors* in the settings veils everything else
  instead
- The Lottie and dotLottie versions at the end of the preview toolbar, and the dotLottie version in a `.lottie` file's editor tab
- The recolor dialog explains that themes need dotLottie 2.0 when the file is 1.0

### Changed

- The *Code* tab plays markers by their name only; the snippet that checked frame ranges is gone

### Fixed

- Playback keys work while the read-only dotLottie details have the focus

## [2026.3.3] - 2026-11-17

### Added

- A *Code* tab in the preview with ready-to-copy code for the open animation: load it, play each marker, react when playback
  enters a marker, start the state machine, set each input and listen to its custom events. For dotLottie Web and React,
  lottie-web, Lottie React Native, dotLottie Android (Compose, View), Lottie Android and Compose, dotLottie iOS, Lottie iOS and
  Flutter; the platform is guessed from the project's build files and can be changed per project
- Recolor from the palette: clicking a color opens a color picker that changes every use of it at once (static values, keyframes,
  gradient stops, solid layers and text), in Lottie JSON and `.lottie` files, as one undoable change. For dotLottie 2.0 files the
  new color can be saved in a new or existing theme instead (the colors become slots); the palette shows the selected theme's
  colors. Alt+click walks through the uses as before

## [2026.3.2] - 2026-11-17

### Added

- Lottie `.json` files are recognized by their content (or a `*.lottie.json` name) and get their own icon; they stay JSON files
- `.lottie` (dotLottie 1.0 and 2.0) files get their own file type and icon
- Lottie JSON opens in a split editor like SVG: the JSON editor next to a live preview, with *Editor*, *Editor and Preview* and
  *Preview* layouts. The preview follows edits, keeps the frame and playing state, and shows the last valid render with the parse
  error while the JSON is invalid
- dotLottie files open in the preview, with pickers for the animation, theme and state machine, and a panel with the manifest and
  the files of the archive
- A timeline under the preview to scrub through the animation: frame ruler and playhead, markers as named segments, optional lanes
  with each layer's in/out range; drag to scrub, double-click a marker to play its segment, click a layer to select it
- Playback controls in the timeline header: play/pause, first/previous/next/last frame, loop and speed
- Playback keys anywhere in the preview: Space plays or pauses, ←/→ step a frame (Shift: ten), Ctrl+←/→ (⌘ on macOS) or Home/End
  jump to the first or last frame
- Markers can be edited on the timeline of Lottie JSON files: add (at a frame or the playhead), rename, delete, drag a marker's
  edges or move them to the playhead. Single-frame markers are shown as flags. Each edit is one undoable change
- Preview toolbar: marker picker, zoom (also Ctrl/⌘ + wheel) and a checkerboard, light, dark or custom background. Rendered by the
  bundled dotLottie player (ThorVG, WebAssembly), without network access
- The animation's color palette above the preview, with every use of a color; in JSON files clicking a color walks through its
  definitions
- Gutter color swatches in Lottie JSON files with a color picker that writes the color back as Lottie components
- Validation and completion from the Lottie Animation Community JSON Schema
- Structure view of the composition, layers, shapes, assets, markers and slots
- Inlay hints naming Lottie codes: layer and shape types, blend and matte modes, parent layers, in/out points and keyframe times in
  seconds, layer names in front of layer objects
- The layer at the caret is outlined in the preview, and clicking the preview moves the caret to the layer under the mouse
- A *State machine* tab for interactive dotLottie files: the running machine's state, its inputs with live values to set, reset or
  fire, and a log of transitions, input changes and custom events
- State machine inputs are added, edited, renamed (with every reference) and deleted from the panel, as undoable edits of the archive
- *Edit State Machine as JSON* opens a state machine of a `.lottie` file in a JSON editor with a bundled schema and a check of its
  references to states, inputs, animations, markers, themes and layers; valid edits are saved into the archive
- *Copy Code* for state machine inputs and custom events, with the matching dotLottie web calls
- *Optimize Lottie…* for Lottie JSON and `.lottie` files, with the size before, after each step and in total: lossless cleanup,
  rounding, hidden layer removal and image compression (scaled down, PNG/JPEG or WebP encoded by the IDE's embedded browser), saved
  as a copy or in place (undoable); a frame comparison renders the animation before and after and asks before writing a result that
  looks different
- The preview's info line shows the file size (and the animation JSON's size for `.lottie` files)
- *Convert to dotLottie 2.0* for dotLottie 1.0 files (context menu and a banner over the file): moves animations, images and audio
  to the 2.0 folders, writes a 2.0 manifest, and reports the 1.0 settings 2.0 cannot hold; undoable
- *Extract Lottie JSON* from a `.lottie` file and *Pack as dotLottie* from a Lottie JSON file
- *Settings › Languages & Frameworks › Lottie*
- Works in IntelliJ-based IDEs from 2026.1 on, including IntelliJ IDEA, WebStorm and Rider (actions in the Solution Explorer); the
  preview needs the IDE's embedded browser (JCEF), and without it the editors keep working with a notice

[Unreleased]: https://github.com/xprees/idea-lottie-plugin/compare/2026.3.8...HEAD
[2026.3.8]: https://github.com/xprees/idea-lottie-plugin/compare/2026.3.7...2026.3.8
[2026.3.7]: https://github.com/xprees/idea-lottie-plugin/compare/2026.3.4...2026.3.7
[2026.3.4]: https://github.com/xprees/idea-lottie-plugin/compare/2026.3.3...2026.3.4
[2026.3.3]: https://github.com/xprees/idea-lottie-plugin/compare/2026.3.2...2026.3.3
[2026.3.2]: https://github.com/xprees/idea-lottie-plugin/commits/2026.3.2
