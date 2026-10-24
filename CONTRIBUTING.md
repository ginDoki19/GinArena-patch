# Contributing

Thanks for helping improve GinArena Patch.

## Development flow

- Work on the `dev` branch.
- Use semantic commit messages:
  - `feat:` new patch or feature
  - `fix:` bug fix
  - `chore:` maintenance that should not create a user-facing release by itself
- Pushes to `dev` create pre-releases. Merge `dev` into `main` with a merge commit (not squash)
  to create a stable release.
- Do not manually edit generated files such as `patches-list.json`, `patches-bundle.json`, or
  `CHANGELOG.md`. The release workflow updates them.
- Do not force-push semantic-release commits or tags.

## Patch rules

- Only target app versions that have been audited. Obfuscated names change between 1DM builds;
  do not declare a new version compatible without checking fingerprints.
- Do not commit APK/APKM files, built `.mpp` files, tokens, or local machine settings.
- Keep the scope to visible UI ads/promotions. Do not add paid-feature unlocks or copied 1DM+
  code/resources.

## Bug reports

Please include the GinArena Patch version, Morphe Manager version, 1DM version/build, device and
Android version, steps to reproduce, screenshots if a promo remains, and `AndroidRuntime` logs if
the app crashes.
