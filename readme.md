<a id="readme-top"></a>

[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![GPL-3.0][license-shield]][license-url]

# Fincord

> [!NOTE]
> **Personal development fork**
> 
> Fincord is a modified fork of [Accord](https://github.com/emylfy/Accord) (no longer maintained) that I use for my own daily use with personal improvements and Jellyfin integration.
> I use this fork to make minor changes to the app so it better fits my daily usage.
> For testing, I run the app on a Google Pixel 5 emulator on my PC, a Samsung Galaxy A56 running the stock ROM, and a Samsung Galaxy A05s running Evolution X v9.9.3 (GSI). This helps me ensure that everything works properly across different environments.

A Jellyfin music client for Android with an Apple-inspired design, installed as `com.bananz0.fincord`.

Fincord continues [Accord](https://github.com/emylfy/Accord), which itself is based on [Gramophone](https://github.com/FoedusProgramme/Gramophone) and [AccordLegacy](https://github.com/FoedusProgramme/AccordLegacy). Those projects and their developers remain credited below.

## Screenshots

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Home.jpg" width="220" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Browse.jpg" width="220" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Library.jpg" width="220" />
</p>
<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Search.jpg" width="220" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Player.jpg" width="220" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Lyrics.png" width="220" />
</p>

## Installation

Build Fincord from this repository. The old [Accord releases](https://github.com/emylfy/Accord/releases/latest)
remain upstream reference artifacts and install as a different application.

## Required Setup

Before building the project, you must create a `package.properties` file in the root directory:

```bash
$ touch package.properties
````

Then add the following content:

```properties
releaseType=SelfBuilt
```

This is required for Gradle to correctly recognize the build type and allow compilation.

## Building

```bash
./gradlew assembleRelease
```

APK will be in `app/build/outputs/apk/release/`.

Release signing is intentionally local. Copy `keystore.properties.example` to
`keystore.properties`, fill in your own Fincord keystore values, and never commit that populated
file or the keystore. Debug builds continue to use Android's normal local debug certificate.

## Credits

Based on [Accord](https://github.com/emylfy/Accord) by [@emylfy](https://github.com/emylfy), which is itself a fork of [Gramophone](https://github.com/FoedusProgramme/Gramophone) and [AccordLegacy](https://github.com/FoedusProgramme/AccordLegacy).

Original developers: [@AkaneTan](https://github.com/AkaneTan), [@lightsummer233](https://github.com/lightsummer233), [@123Duo3](https://github.com/123Duo3)

Fork maintained by [@v3ndable](https://github.com/v3ndable)

## License
This project remains under the GNU General Public License v3.0.

GPL-3.0 — see [LICENSE](LICENSE) for details.

<p align="right">(<a href="#readme-top">back to top</a>)</p>

[contributors-shield]: https://img.shields.io/github/contributors/v3ndable/Accord.svg?style=for-the-badge
[contributors-url]: https://github.com/v3ndable/Accord/graphs/contributors
[forks-shield]: https://img.shields.io/github/forks/v3ndable/Accord.svg?style=for-the-badge
[forks-url]: https://github.com/v3ndable/Accord/network/members
[stars-shield]: https://img.shields.io/github/stars/v3ndable/Accord.svg?style=for-the-badge
[stars-url]: https://github.com/v3ndable/Accord/stargazers
[issues-shield]: https://img.shields.io/github/issues/v3ndable/Accord.svg?style=for-the-badge
[issues-url]: https://github.com/v3ndable/Accord/issues
[license-shield]: https://img.shields.io/github/license/v3ndable/Accord.svg?style=for-the-badge
[license-url]: https://github.com/v3ndable/Accord/blob/main/LICENSE
