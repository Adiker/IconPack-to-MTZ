# Pull request

## Summary

<!-- What changed and why? -->

## User impact

<!-- Describe visible behavior, compatibility, performance, or security impact. -->

## Validation

- [ ] `./gradlew --no-daemon --max-workers=1 test`
- [ ] `./gradlew --no-daemon --max-workers=1 lint`
- [ ] Relevant build or managed-device tests
- [ ] `git diff --check`

## Safety and licensing

- [ ] No APK, converted third-party icons, signing credentials, or private URI values are included.
- [ ] New archive/XML/bitmap behavior preserves bounded-input protections.
- [ ] New dependencies and fixtures have compatible licenses and updated notices.
- [ ] HyperOS, Xiaomi Themes, or zFont claims identify whether a physical device was tested.
