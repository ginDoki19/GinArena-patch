package app.template.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    /**
     * Morphe patch metadata has no dedicated stock-app download field. The exact APKMirror
     * link intentionally lives in the project README instead of user-facing descriptions, so
     * Manager's version card stays readable. The target description below only identifies the
     * required APKM variant.
     */

    /** SHA-256 of the official Vicky Bonick signing certificate used by 1DM 20.0 (30257). */
    private const val ONEDM_SIGNER_SHA256 = "9f48b6b2741fe7e4f78ea7375f469e99086c07f45a6c7f38407f0483d2451ce7"

    val COMPATIBILITY_ONEDM = Compatibility(
        name = "1DM",
        packageName = "idm.internet.download.manager",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x43A047,
        signatures = setOf(ONEDM_SIGNER_SHA256),
        targets = listOf(
            AppTarget(
                version = "20.0",
                versionCode = 30257,
                minSdk = 24,
                description = "Universal APKM (160-640 dpi, Android 7.0+), build 30257"
            )
        )
    )
}
