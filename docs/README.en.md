# IconPack to HyperOS MTZ

[Polski](../README.md) · **English**

IconPack to HyperOS MTZ is a local Android application that converts standard
Android icon-pack APKs, including Arcticons, into MIUI/HyperOS `.mtz` icon
themes.

> [!IMPORTANT]
> The archive structure is covered by automated fixtures and emulator tests.
> Import into Xiaomi Themes or zFont on a physical HyperOS device has not been
> confirmed yet. See the [compatibility notes](COMPATIBILITY.md).

## Features

- Reads text and compiled `appfilter.xml` without installing the source APK.
- Supports VectorDrawable, adaptive icons, PNG, WebP, JPEG, and asset SVGs.
- Renders every unique source once and reuses a bounded disk LRU cache.
- Provides optimized, full-compatibility, and package-only naming strategies.
- Creates a standalone MTZ or replaces only `icons` in a working base theme.
- Produces versioned JSON and human-readable text reports.
- Runs long conversions in a cancellable foreground service.
- Stores conversion history locally with Room.
- Can optionally use Shizuku for a more complete installed-package list.
- Provides Polish and English UI.

The application declares neither `INTERNET` nor `QUERY_ALL_PACKAGES`.

## Requirements

- Android Studio compatible with Android Gradle Plugin 9.3;
- Android SDK Platform 37 and Build Tools 37.0.0;
- a full JDK 17 or newer;
- JDK 21 for Robolectric tests that cover API 37;
- Android 11 (API 30) or newer at runtime.

## Build

```bash
git clone https://github.com/Adiker/IconPack-to-MTZ.git
cd IconPack-to-MTZ
./gradlew assembleDebug
```

For a memory-conservative full validation, run each task separately:

```bash
./gradlew --no-daemon --max-workers=1 test
./gradlew --no-daemon --max-workers=1 lint
./gradlew --no-daemon --max-workers=1 assembleDebug
./gradlew --no-daemon --max-workers=1 bundleRelease
```

Managed-device smoke tests:

```bash
./gradlew --no-daemon --max-workers=1 pixel2Api30DebugAndroidTest
./gradlew --no-daemon --max-workers=1 pixel2Api37DebugAndroidTest
```

`bundleRelease` creates an unsigned AAB. Signing credentials are deliberately
not stored in the repository.

## Usage

1. Select an icon-pack APK through Android's document picker.
2. Optionally select a known-working base MTZ.
3. Choose an output directory, conversion mode, and naming strategy.
4. Analyze the APK. Up to 64 representative icons are rendered into the cache
   to estimate output size.
5. Start generation. The foreground service keeps working outside the UI and
   exposes cancellation.

Full mode always converts the entire appfilter and needs neither root nor
Shizuku. Installed-apps mode is an optional optimization and can be incomplete
because of Android package-visibility filtering.

## Security and privacy

APK and MTZ inputs are untrusted. The pipeline enforces limits for archive
entries, expanded bytes, compression ratio, XML depth, source bitmap size, and
output aliases. It blocks external XML entities, Zip Slip, path traversal, and
unsafe output names.

All processing is local. Operational copies live only in the application's
private cache and are removed after completion or cancellation. Exported
reports redact private paths and URI values.

Please report suspected vulnerabilities according to
[SECURITY.md](../SECURITY.md), not in a public issue.

## Documentation

- [Architecture](ARCHITECTURE.md)
- [Compatibility and limitations](COMPATIBILITY.md)
- [Contributing](../CONTRIBUTING.md)
- [Third-party notices](../THIRD_PARTY_NOTICES.md)

## License

Project code is licensed under the [Apache License 2.0](../LICENSE). The
repository contains no third-party icon packs. Users remain responsible for
the rights required to convert, use, or redistribute icons from a selected
APK.
