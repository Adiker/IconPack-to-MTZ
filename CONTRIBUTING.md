# Contributing

Thank you for helping improve IconPack to HyperOS MTZ.

## Ground rules

- Keep conversion fully local and do not add network permissions.
- Treat APK and MTZ inputs as hostile.
- Do not commit third-party icon packs, converted icons, signing keys, private
  URI values, or device-specific user data.
- Use synthetic or self-authored fixtures with an explicit compatible license.
- Document compatibility claims with the exact device or test environment.
- Do not claim Xiaomi Themes or zFont compatibility without a real import
  test.

## Branches and pull requests

- Do not commit directly to `main` unless the repository owner explicitly asks
  for it.
- Use `feature/`, `fix/`, `refactor/`, `docs/`, or `chore/` branch prefixes.
- Keep each pull request focused on one coherent change.
- Update the documentation that owns changed behavior.
- Include tests for parsing, naming, security limits, rendering, or archive
  output when those areas change.

Short Conventional Commit-style subjects are preferred:

```text
feat: support another appfilter location
fix: reject normalized alias collisions
docs: clarify HyperOS import limitations
chore: update Android build dependencies
```

Small fixes and documentation changes normally use squash merge. Larger
features with independently meaningful commits may use rebase-and-merge.

## Development setup

Requirements:

- Android Studio compatible with AGP 9.3;
- Android SDK Platform 37 and Build Tools 37.0.0;
- a full JDK 17 or newer;
- JDK 21 for the API 37 Robolectric suite.

Use memory-conservative validation commands:

```bash
./gradlew --no-daemon --max-workers=1 test
./gradlew --no-daemon --max-workers=1 lint
./gradlew --no-daemon --max-workers=1 assembleDebug
./gradlew --no-daemon --max-workers=1 bundleRelease
git diff --check
```

For platform-facing changes, also run:

```bash
./gradlew --no-daemon --max-workers=1 pixel2Api30DebugAndroidTest
./gradlew --no-daemon --max-workers=1 pixel2Api37DebugAndroidTest
```

Run managed devices one at a time. They can consume substantially more memory
than JVM tests.

## Pull request checklist

- [ ] The change has a focused purpose and no unrelated files.
- [ ] No source APK, converted icon, credential, or private URI is included.
- [ ] Relevant unit or integration tests were added or updated.
- [ ] `test`, `lint`, and the appropriate build task pass.
- [ ] User-facing behavior and limitations are documented.
- [ ] New dependencies have an acceptable license and are listed in
      `THIRD_PARTY_NOTICES.md`.
- [ ] Physical-device compatibility claims include reproducible environment
      details.

## Licensing

By contributing, you agree that your contribution is licensed under the
repository's [Apache License 2.0](LICENSE). Fixture artwork must be original,
CC0, or otherwise clearly compatible with redistribution in this repository.
