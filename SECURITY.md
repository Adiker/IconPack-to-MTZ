# Security policy

## Reporting a vulnerability

Do not disclose suspected vulnerabilities, malicious archive samples,
credentials, or sensitive proofs of concept in a public issue.

Use GitHub's private
[security advisory form](https://github.com/Adiker/IconPack-to-MTZ/security/advisories/new)
and include:

- the affected revision and Android version;
- a minimal description of the crafted input;
- expected and observed behavior;
- impact, including memory, storage, or path effects;
- reproduction steps that do not contain personal data.

Do not attach copyrighted icon packs. A minimal synthetic archive is preferred.

## Security model

APK and MTZ files are untrusted. Security-sensitive areas include:

- ZIP entry validation, compression ratios, and expanded-byte accounting;
- XML entity, depth, and reference handling;
- bitmap dimensions and memory usage;
- output filename normalization and collision handling;
- SAF document publication and cleanup after cancellation;
- foreground-service lifecycle and persisted URI permissions;
- optional Shizuku package enumeration.

The application performs conversion locally and does not request internet
access. Shizuku integration is opt-in and read-only.

## Supported versions

Until the first stable release, security fixes are provided on the latest
revision of `main`. Older development snapshots are not maintained separately.

## Disclosure

After a fix is available, the maintainers may publish a GitHub Security
Advisory describing impact and affected versions without distributing a
weaponized or copyrighted sample.
