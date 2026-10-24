package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /** Exact APKMirror page for the official universal 1DM 20.0 (30257) APKM. */
    const val STOCK_APKM_URL = "https://www.apkmirror.com/apk/Vicky%20Bonick/idm-download-video-movie-music-anime-torrent/1dm-browser-video-download-20-0-release/1dm-browser-video-download-20-0-2-android-apk-download/"

    /** SHA-256 of the official Vicky Bonick signing certificate used by 1DM 20.0 (30257). */
    private const val ONEDM_SIGNER_SHA256 = "9f48b6b2741fe7e4f78ea7375f469e99086c07f45a6c7f38407f0483d2451ce7"

    val COMPATIBILITY_ONEDM = Compatibility(
        name = "1DM",
        description = "1DM: Browser & Video Download (free). Use the official 1DM 20.0 (30257) universal APKM (Android 7.0+, 160-640 dpi). Stock APKM: $STOCK_APKM_URL",
        packageName = "idm.internet.download.manager",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x43A047,
        signatures = setOf(ONEDM_SIGNER_SHA256),
        targets = listOf(
            AppTarget(
                version = "20.0",
                versionCode = 30257,
                minSdk = 24,
                description = "Official universal APKM for 1DM 20.0 (30257), Android 7.0+ / 160-640 dpi. Download: $STOCK_APKM_URL Do not use the separate 20257 variant."
            )
        )
    )
}
