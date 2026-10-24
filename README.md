# <img src="assets/icon.png" width="36" alt=""> GinArena Patch

**GinArena Patch** is a [Morphe](https://morphe.software/) patch bundle for the free **1DM**
Android app (`idm.internet.download.manager`). It contains one default-on patch, also named
**GinArena Patch**, implemented entirely as bytecode changes.

### How to use these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=sagarkhansau19-svg/GinArena-patch

Or manually add this repository as a remote source in Morphe Manager:

```text
github.com/sagarkhansau19-svg/GinArena-patch
```

## 🩹 Patches list

<!-- PATCHES_START EXPANDED -->

#### A list of patches will automatically be shown here after a release is created.

<!-- PATCHES_END -->

## What it does

It removes visible advertising and promotion UI from the free app while keeping the app's own
flow intact:

- Consent / ad-SDK initialization is short-circuited, but the app's continuation callbacks still run.
- Persistent network banners and the built-in 1DM+ house/promo banners are never loaded or shown,
  and their views are forced `GONE`.
- Full-screen interstitial paths are skipped, again running the caller's continuation.
- The navigation drawer **“Upgrade to 1DM+”** item is hidden.
- The **“1DM+ on sale”** toolbar subtitle is disabled (the legitimate *Update app* subtitle is kept).
- The empty banner container (`MaxHeightLinearLayout`) is collapsed to 0×0 so no blank strip remains.

- The downloads-home promo strip (`offer_vpnLL`) is forced onto the app's own hide path, so the
  ProtonVPN installation offer—and the other promo messages rotated through that same strip—are not
  shown. This does not alter 1DM's actual VPN settings or functionality.
- Sponsored browser start-page tiles are removed:
  - the modern start page no longer selects database `BannerInfo` tile ads (`isTileAd()` now
    returns false), including previously cached *Play Quiz / Play Games* promotions;
  - the legacy/simple start page's two remotely configured sponsored shortcuts are nulled before
    its HTML is built.

User-created browser shortcuts and the **Add Shortcut** tile use a separate path and remain available.

GinArena Patch does **not** unlock any 1DM+ paid features (scheduler, higher part/concurrency
caps, paid DNS entries, …), and it contains no 1DM+ code or resources.

## Target app

| Field | Value |
| --- | --- |
| Package | `idm.internet.download.manager` |
| Version | **20.0** (versionCode 30257), stable |
| Input file | Official **universal APKM** (base + ABI/dpi splits) so both `arm64-v8a` and `armeabi-v7a` devices are covered |
| Stock download | [APKMirror — 1DM 20.0 (universal) (160-640dpi) (Android 7.0+)](https://www.apkmirror.com/apk/Vicky%20Bonick/idm-download-video-movie-music-anime-torrent/1dm-browser-video-download-20-0-release/1dm-browser-video-download-20-0-2-android-apk-download/) |
| Signing certificate SHA-256 | `9f48b6b2741fe7e4f78ea7375f469e99086c07f45a6c7f38407f0483d2451ce7` |
| APKM SHA-256 | `4c36d570f5cdbbbdbba4d2217aba33ed0e1bbf6179e6eb3b9eb17d936124b005` |
| `base.apk` SHA-256 | `b55935a4435da5d3623aa8c94384039d9f07128a58ba1a93f427a5a21924c3ab` |

Do **not** use the separate **20257** variant for this patch.

Other versions (including 20.1 beta) are **not** declared compatible: obfuscated class/method
names differ between builds, so the fingerprints must be re-audited per version.

### Stock download note

Morphe patch metadata does not have a dedicated “stock app download URL” field, and Morphe Manager
does not read a download destination from a patch bundle. In this release the exact APKMirror URL
is included in this README and in the patch/compatibility target descriptions. Compatibility is
also pinned to the package, APKM file type, version **20.0**, versionCode **30257** for all ABIs,
`minSdk` **24**, and the official signing-certificate SHA-256.

## Usage (Morphe Manager)

1. Add this repository as a remote patch source using the link above.
2. Download the exact official 20.0 universal APKM above and verify the hash if you can.
3. Back up 1DM data if you already use it — patching re-signs the app, so you must **uninstall
   the official 1DM first** (signature mismatch), which clears its private settings.
4. In Morphe Manager, select the 1DM 20.0 APKM, enable **GinArena Patch**, then patch and install.
   If a third-party source or patch is not visible, enable Expert Mode in Morphe settings.
5. Patched builds cannot update from the Play Store; repeat the process for future versions.

### Bundle icon

The Gintama logo is included as `patches-bundle.png` for the remote patch-source icon and as
`assets/icon.png` for this README. A Morphe `.mpp` file does not embed the bundle PNG by itself;
Manager picks it up from beside the remote bundle metadata.

## Building from source

Requirements: JDK 21 and a GitHub token with `read:packages` for Morphe's GitHub Packages
dependencies. Configure `gpr.user` / `gpr.key` in your user Gradle properties, or set
`GITHUB_ACTOR` / `GITHUB_TOKEN`. Then:

```bash
bash gradlew :patches:buildAndroid
# output: patches/build/libs/patches-*.mpp
```

## Development

- Make changes on the `dev` branch.
- Use semantic commit messages (`feat:`, `fix:`, `chore:`). `dev` creates pre-releases; merging
  `dev` into `main` (merge commit, not squash) creates stable releases.
- Do not manually edit generated files such as `patches-list.json`, `patches-bundle.json`, or
  `CHANGELOG.md`; the release workflow updates them.
- A new 1DM version requires a fresh fingerprint audit before compatibility is declared.

## Notes

- This patch is for an app you legitimately obtained; it only patches files on your device.
  Support the 1DM developer by buying 1DM+ if you want the paid features.
- Ad SDK classes remain in the APK; their entry points are simply never reached. Removing classes
  would risk breaking reflective references, while dead code is harmless.

## License

GinArena Patch is licensed under the [GNU General Public License v3.0](LICENSE).
