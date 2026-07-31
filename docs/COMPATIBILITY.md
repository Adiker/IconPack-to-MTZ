# HyperOS and MIUI compatibility

## Validation status

The project currently validates parser behavior, rendered PNG output, internal
`icons` structure, standalone MTZ construction, and replacement of `icons` in
a synthetic base theme.

| Environment | Status |
| --- | --- |
| JVM and Robolectric API 30/37 | Passed |
| Managed Android emulator API 30 | Passed |
| Managed Android emulator API 37 | Passed |
| Synthetic standalone MTZ | Passed |
| Synthetic base-theme MTZ | Passed |
| Physical POCO F8 Ultra with HyperOS | MTZ import tested successfully |
| Other physical Xiaomi devices with MIUI/HyperOS | Not tested |
| zFont import | Not tested |

Automated structural tests do not prove that every regional Xiaomi Themes
build will accept a locally imported MTZ. The successful POCO F8 Ultra test is
device-specific and does not establish compatibility for every HyperOS device,
region, or Themes version.

## Icon-pack input

Supported mapping and resource forms include:

- text `assets/appfilter.xml`;
- compiled `res/xml/appfilter.xml`;
- common `res/raw`, `appfilter_*`, `app_filter`, and `icon_config` variants;
- VectorDrawable and adaptive-icon XML;
- PNG, WebP, and JPEG density variants;
- SVG files stored in assets.

The analyzer reports missing drawables, unsupported XML features, malformed
components, collisions, corrupt resources, and fallback decisions.

## Theme layout

Standalone output contains:

```text
description.xml
icons
preview/preview_icons_0.jpg
```

The inner `icons` ZIP stores static PNG files under:

```text
res/drawable-xxhdpi/
```

In base-theme mode, every root entry except the exact `icons` entry is copied.
The base `description.xml`, previews, and unrelated modules remain unchanged.

## Known limitations

- Xiaomi does not expose one stable local-import contract for every MIUI,
  HyperOS, Themes application, account, and region combination.
- Some devices may require a theme created for the same platform generation
  or a known-working base MTZ.
- Xiaomi's publishing rules and local sideloading behavior are different.
  Large local files are allowed by the converter, but a warning is shown above
  the commonly documented 80 MiB publication threshold.
- Adaptive icons are flattened into static PNGs and do not retain live mask or
  parallax behavior.
- Vector fallback supports paths, groups, clipping, fill/stroke, alpha, and
  trim path. Complex compiled gradients or uncommon resource references may
  require a fallback or be reported as unsupported.
- Installed-apps mode can be incomplete because Android filters package
  visibility. Full mode is the portable default.
- Shizuku improves installed-package discovery only; it does not grant theme
  import capabilities.

## Arcticons benchmark

The non-committed benchmark used the official Arcticons 14.9.1 normal APK and
observed:

- 47,070 appfilter entries;
- 32,469 unique packages;
- 13,624 unique drawable names;
- 33,025 generated aliases after validation;
- 5.9 seconds for analysis;
- 16.0 seconds for rendering and construction of the inner `icons` archive.

This benchmark ran under Robolectric on a desktop host. Robolectric encoded
each 168×168 PNG as an effectively uncompressed bitmap, making the inner
archive artificially larger than 3 GiB. The outer MTZ step was intentionally
skipped when the hard per-entry limit was reached. These byte-size results are
not representative of Android's production PNG encoder.

The APK was downloaded from the
[official Arcticons 14.9.1 release](https://github.com/Arcticons-Team/Arcticons/releases/tag/14.9.1)
and was removed after the benchmark. No Arcticons files are committed to this
repository.

## Recommended device validation

Before calling a release compatible with a particular HyperOS version:

1. Generate a small standalone MTZ from the fixture or a licensed icon pack.
2. Test import and application in Xiaomi Themes for the target region.
3. Repeat with a known-working base MTZ.
4. Confirm package-only and activity-specific aliases in the launcher.
5. Reboot or restart the launcher and verify icons remain applied.
6. Record the device, Android, HyperOS, Themes, region, and import method.

Successful reports can be contributed without uploading copyrighted source
APKs or converted icon files.
