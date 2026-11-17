# Changelog

## [Unreleased]

### Added

- Lottie `.json` files are recognized by their content (or a `*.lottie.json` name) and get the Lottie icon; they stay JSON files
- `.lottie` (dotLottie 1.0 and 2.0) files get their own file type and icon
- Lottie JSON opens in a split editor like SVG: the JSON editor next to a live preview, with *Editor*, *Editor and Preview* and
  *Preview* layouts. The preview follows edits, keeps the frame and playing state, and shows the last valid render with the parse
  error while the JSON is invalid
- dotLottie files open in the preview, with pickers for the animation, theme and state machine, and a panel with the manifest and
  the files of the archive
- Preview controls: play/pause, loop, speed, frame scrubber, markers, zoom (also Ctrl/⌘ + wheel) and a checkerboard, light, dark
  or custom background. Rendered by the bundled dotLottie player (ThorVG, WebAssembly), without network access
- The animation's color palette above the preview, with every use of a color; in JSON files clicking a color walks through its
  definitions
- Gutter color swatches in Lottie JSON files with a color picker that writes the color back as Lottie components
- Validation and completion from the Lottie Animation Community JSON Schema
- Structure view of the composition, layers, shapes, assets, markers and slots
- Inlay hints naming Lottie codes: layer and shape types, blend and matte modes, parent layers, in/out points and keyframe times in
  seconds, layer names in front of layer objects
- The layer at the caret is outlined in the preview, and clicking the preview moves the caret to the layer under the mouse
- *Extract Lottie JSON* from a `.lottie` file and *Pack as dotLottie* from a Lottie JSON file
- *Settings › Languages & Frameworks › Lottie*
