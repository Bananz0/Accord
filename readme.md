# Accord

> **Personal development fork**
> 
> This repository is a modified fork of [Accord](https://github.com/emylfy/Accord) (no longer maintained) that I use for my own daily use with small personal tweaks and adjustments.
> I use this fork to make minor changes to the app so it better fits my daily usage.
> For testing, I mainly run the app on a Google Pixel 5 (running an emulator setup on my PC) and a Samsung Galaxy A05s with Evolution X v9.9.3 (GSI). This helps me make sure everything works properly across both setups.

---

A local music player for Android with an Apple-inspired design. Supports synced lyrics (LRC/SRT), gapless playback, and third-party equalizers.

Fork of [Accord](https://github.com/emylfy/Accord), which itself is based on [Gramophone](https://github.com/AkaneTan/Gramophone) and [AccordLegacy](https://github.com/FoedusProgramme/AccordLegacy), with small personal adjustments for daily use, bug fixes, and updated dependencies.

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

Download the original Accord-APK (no longer maintained) from [GitHub Releases](https://github.com/emylfy/Accord/releases/latest).

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
git clone https://github.com/emylfy/Accord.git
cd Accord
./gradlew assembleRelease
```

APK will be in `app/build/outputs/apk/release/`.

## Credits

Based on [Accord](https://github.com/emylfy/Accord) by [@emylfy](https://github.com/emylfy), which is itself a fork of [Gramophone](https://github.com/AkaneTan/Gramophone) and [AccordLegacy](https://github.com/FoedusProgramme/AccordLegacy).

Original developers: [@AkaneTan](https://github.com/AkaneTan), [@lightsummer233](https://github.com/lightsummer233), [@123Duo3](https://github.com/123Duo3)

Fork maintained by [@v3ndable](https://github.com/v3ndable)

## License
This project remains under the GNU General Public License v3.0.

GPL-3.0 — see [LICENSE](LICENSE) for details.
