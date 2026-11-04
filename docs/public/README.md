# Streaming Radio source archive

Streaming Radio by Impostor Labs is a native Android Automotive OS internet-radio app.
This README accompanies the standalone source export. The full project documentation,
screenshots and current handoff are in the [GitHub repository](https://github.com/eyyit/streaming-radio-app).

See [BUILD.md](BUILD.md) to build with JDK17 and Android SDK36. The Gradle wrapper
requires network access to retrieve build tools and dependencies. No signing key is
included or needed for an unsigned build.

- [Privacy policy](docs/privacy.html)
- [Security status](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [Design rules](docs/DESIGN-SYSTEM.md)
- [Third-party notices](THIRD-PARTY-NOTICES.md)

Artwork is enabled by default unless a saved preference turns it off. Release
Apple/iTunes lookup remains disabled. No stations or personal playlist are bundled.
The custom parked player and the vehicle's native media player have different layouts.
Passing tests does not certify vehicle safety or resolve the reported system-audio issue.

This is a [GPL-3.0-or-later](LICENSE) fork of
[szczepl/twinsen-radio](https://github.com/szczepl/twinsen-radio), originally
copyright 2026 Leszek Szczepanowski. Original copyrights and attribution are retained.
