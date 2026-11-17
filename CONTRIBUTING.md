# Contributing to Lottie for JetBrains IDEs

Thanks for helping. Bug reports, fixes, new features, tests and documentation are all welcome. This guide covers how to get
the project running, how the code is organized, and what a pull request needs before it can be merged.

By taking part you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Report security problems privately, as
described in [SECURITY.md](SECURITY.md), not in a public issue.

## Contents

- [Before you start](#before-you-start)
- [Setting up](#setting-up)
- [Project layout](#project-layout)
- [Building and running](#building-and-running)
- [Tests](#tests)
- [Coding guidelines](#coding-guidelines)
- [IntelliJ Platform rules](#intellij-platform-rules)
- [Commits, changelog and pull requests](#commits-changelog-and-pull-requests)
- [Releases (maintainers)](#releases-maintainers)
- [Licensing](#licensing)

## Before you start

- **Small fixes** (a typo, an obvious bug with a test) can go straight to a pull request.
- **Bigger changes** (a new feature, a new setting, a change to how files are recognized or rendered): open an issue first and
  describe the problem and the approach.
- **The formats are the reference.** Behaviour follows the [Lottie specification](https://lottie.github.io/lottie-spec/), the
  [dotLottie specification](https://dotlottie.io/spec/2.0/) and what the dotLottie player renders. When you change how a file is
  read or written, link the part of the spec you followed.
- For a starting point, look for issues labelled `good first issue` or `help wanted`, or ask in an issue.

## Setting up

You need:

- **JDK 21** (any distribution; Gradle's toolchain support picks it up).
- **IntelliJ IDEA** (Community is enough) for development, with the *Plugin DevKit* plugin.
- Network access to the npm registry for the first build (the player and the schema are downloaded, see below). No Node.js needed.

```shell
git clone https://github.com/xprees/idea-lottie-plugin.git
cd idea-lottie-plugin
./gradlew build
```

Open the directory in IntelliJ IDEA as a Gradle project. The shared run configurations in `.run/` (*Run Plugin*, *Run Tests*,
*Run Verifications*) appear in the toolbar.

The first build downloads the IntelliJ Platform (about 1.5 GB) into the Gradle cache.

## Project layout

```
src/main/kotlin/cz/xprees/lottie/
  json/          a small JSON reader/writer (no platform dependencies)
  detect/        recognizing Lottie JSON from the first bytes of a file
  dotlottie/     reading and writing .lottie archives (dotLottie 1.0 and 2.0)
  palette/       Lottie colors and the color scanner behind the palette and the gutter swatches
  model/         the animation header, layers, markers and readable names for Lottie codes
  lang/          the dotLottie file type, which files are Lottie, file icons
  preview/       the JCEF preview, its toolbar and panels, the split editor and the editor providers
  editor/        JSON schema, structure view, inlay hints, color provider, JSON PSI helpers
  actions/       Extract Lottie JSON, Pack as dotLottie
  settings/      Settings › Languages & Frameworks › Lottie
src/main/resources/
  META-INF/plugin.xml               extension registrations
  messages/LottieBundle.properties  every user-visible string
  web/                              the preview page (HTML, CSS, JS); the player is added by the build
src/test/kotlin/   tests
src/test/testData/ sample animations and archives
```

The README's [How it works](README.md#how-it-works) section explains the preview design.

## Building and running

```shell
./gradlew runIde          # a sandbox IDE with the plugin installed
./gradlew test            # all tests
./gradlew test --tests '*PaletteTest'   # one test class while you iterate
./gradlew buildPlugin     # build/distributions/lottie-<version>.zip
./gradlew verifyPlugin    # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

`runIde` starts the oldest supported IDE (`platformVersion` in `gradle.properties`). The files in `src/test/testData/` are a
quick way to try the preview, the palette and the dotLottie panel.

### Bundled web assets

The player (`@lottiefiles/dotlottie-web`) and the schema (`@lottie-animation-community/lottie-specs`) are npm packages that the
`fetchDotLottieWeb` and `fetchLottieSchema` tasks download at build time, check against the integrity hash in `gradle.properties`
and unpack into `build/generated/npm/`. Dependabot doesn't see them. To update one:

1. Change its version and integrity in `gradle.properties` (`npm view <package>@<version> dist.integrity`).
2. Check the player API the preview page uses (`src/main/resources/web/preview.js`) against the new version's typings.
3. Update `THIRD_PARTY_NOTICES.md` and add a changelog entry.

## Tests

Every change in behaviour needs a test that fails without it.

- Model code (`json/`, `detect/`, `dotlottie/`, `palette/`, `model/`) is tested with plain JUnit tests.
- Platform features extend `BasePlatformTestCase` and work on files created with `myFixture.configureByText`; see
  `LottiePlatformTest`.
- The preview page runs in JCEF and has no automated test in CI. When you change `web/preview.js` or the bridge in
  `PreviewBrowser`, try it in `runIde` with the sample files (JSON and `.lottie`, highlight and picking) and say so in the pull
  request.
- **Assert exact results**, and **don't skip, disable or loosen a failing test.** Find the cause.

## Coding guidelines

The code follows the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html), set up in
`.editorconfig`: 4-space indentation and lines up to 140 columns. Use *Code › Reformat Code* in IntelliJ IDEA before you commit.

- **Write KDoc for every non-private declaration.** Say what it is for and the cases that aren't obvious.
- **Name things after the formats.** Use the Lottie and dotLottie terms (layer, shape, precomposition, slot, theme, state machine)
  and keep Lottie's short keys (`ty`, `ip`, `op`) recognizable in code that reads them.
- **Keep the model code free of platform dependencies**, so it stays testable without a fixture.
- **No dead code or commented-out code.** Remove it; git keeps the history.
- **User-visible strings go in `LottieBundle.properties`** and are read with `LottieBundle.message(...)`. Write them in sentence
  case, following the [IntelliJ Platform UI guidelines](https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html).
- **The preview stays offline.** The page may only load what the plugin serves from its private origin. Don't add CDN URLs, and
  keep the Content Security Policy in `preview.html` strict.
- **Mind performance.** Detection runs for file icons; keep it bounded (it reads at most 64 KB). The color provider and inlays
  run while the user types; cache per file modification.

## IntelliJ Platform rules

The plugin supports every IDE from `platformVersion` (2026.1) on, with no upper build limit, and installs without a restart.
Pull requests are checked against these rules by CI:

- **Use only public, stable APIs.** The Plugin Verifier fails the build on internal API use and warns on deprecated or
  experimental API.
- **Compile against the oldest supported platform.** Don't raise `platformVersion` to get a newer API without discussing it first.
- **Stay a dynamic plugin.** Register everything in `plugin.xml`, use light services (`@Service`), and give listeners, browsers
  and other resources a parent `Disposable`.
- **Respect threading rules.** Read PSI in a read action, write in a write action, and keep I/O and parsing off the EDT.
- **Don't change plain JSON.** Everything the plugin adds must stay off in `.json` files that aren't Lottie.

Run `./gradlew verifyPlugin` locally when you touch platform APIs. CI verifies only the newest IDEA, WebStorm and Rider by
default. Add the `full-verify` label to a pull request to verify the whole version matrix (from its next push on).

## Commits, changelog and pull requests

### Commits

- Write the subject in the imperative and sentence case, without a trailing period: *Show markers of precompositions in the
  marker picker*. Keep it under about 72 characters.
- Use the body to explain why, and anything a reviewer would otherwise have to work out from the diff.
- Keep each commit focused. Don't mix refactoring, formatting and behaviour changes in one commit.

### Changelog

Every user-visible change gets an entry under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md), in the
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections *Added*, *Changed*, *Fixed* or *Removed*. Write it for
users: what they can now do or what no longer goes wrong, not which class changed. The release workflow turns these entries
into the Marketplace release notes. Internal changes (tests, CI, refactoring) need no entry.

### Pull requests

1. Fork the repository (or create a branch, if you have write access) and branch from `master`.
2. Make the change, with tests and a changelog entry.
3. Run `./gradlew test` (the full suite) and, if you touched platform APIs, `./gradlew verifyPlugin`.
4. Open the pull request and fill in the template: what changed, why, and how you tested it.
5. CI must be green: *Build*, *Test* and *Verify plugin*. Fix failures in the pull request. "Flaky" is not a cause; find out
   why a test failed.
6. To resolve conflicts, merge `master` into your branch. Don't force-push a branch someone else is reviewing; push new commits
   instead. The maintainer may squash the pull request when merging it.

One feature or fix per pull request. Large features can be split into a series of pull requests that each build and pass the
tests on their own.

### AI-assisted contributions

Generated code is welcome under the same rules as any other code: you are responsible for it, you have read and understood it,
and it comes with tests. Say in the pull request if a substantial part was generated.

## Releases (maintainers)

Every merge to `master` that passes CI is tagged with the next version, and a draft GitHub release is created with the built
plugin attached. The README's [Versions and releases](README.md#versions-and-releases) section has the details.

To ship:

1. Check the draft release notes. They come from `## [Unreleased]` in the changelog.
2. Publish the draft. The *Release* workflow signs the build and publishes it to the JetBrains Marketplace.
3. **Merge the `Changelog update - <version>` pull request** the workflow then opens. It moves the released entries under the
   new version heading. Until it is merged, the next release's notes repeat this release's entries.

## Licensing

The project is licensed under [Apache-2.0](LICENSE). By contributing, you agree that your contribution is licensed under the
same terms (Apache-2.0, section 5). Only contribute code you have the right to submit. Code or assets from other projects
must have a compatible license and be listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
