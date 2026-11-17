# Security policy

## Supported versions

Only the latest release on the JetBrains Marketplace gets security fixes. Update the plugin
(*Settings › Plugins › Installed › Lottie › Update*) before you report a problem.

## Reporting a vulnerability

**Don't open a public issue for a security problem.** Report it privately through GitHub:

1. Open the repository's [Security tab](https://github.com/xprees/idea-lottie-plugin/security).
2. Choose **Report a vulnerability** and fill in the form.

Include what you can of:

- the plugin version and the IDE (name, version, operating system);
- what an attacker can do, and under which conditions (for example: opening a crafted `.lottie` or Lottie `.json` file, previewing
  it, running Extract or Pack on it);
- steps or a minimal file that reproduce it.

You can expect an acknowledgement within a week. Once the problem is confirmed, a fix is released as soon as possible and the
advisory is published with the release. You are credited in the advisory unless you prefer not to be.

## Scope

The plugin reads Lottie files of the open project, unpacks `.lottie` ZIP archives, renders animations in an embedded Chromium
page (JCEF) and writes files when you run Extract or Pack. Problems in these areas are in scope, for example:

- script execution outside the preview page, or network access, triggered by opening or previewing a file (the preview page is
  served from a private origin and may not load anything else; state machines may not open URLs);
- reading or writing files outside the project, or leaking their content (for example through archive entries with `..` paths,
  or image references that point outside the animation's folder when packing);
- the IDE hanging or running out of memory on a small crafted file (archives are limited to 128 MB per entry and 512 MB in total;
  report a file that gets past this, or one small file that makes the IDE unusable).

Vulnerabilities in the IntelliJ Platform or JCEF belong to [JetBrains](https://www.jetbrains.com/privacy-security/issues-fixed/),
and those in the bundled player to [LottieFiles](https://github.com/LottieFiles/dotlottie-web/security).
