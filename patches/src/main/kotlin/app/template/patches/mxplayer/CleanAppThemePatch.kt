package app.template.patches.mxplayer

import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.COMPATIBILITY_MX_PLAYER_AD
import com.android.tools.smali.dexlib2.AccessFlags

@Suppress("unused")
val cleanAppThemePatch = bytecodePatch(
    name = "Clean App Theme",
    description = "Removes online Other Themes (such as Zodiac) from the App Theme screen while keeping " +
        "Adaptive / Light / Dark modes and all 31 Classic Themes color swatches available offline and online.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MX_PLAYER_AD)

    dependsOn(mxIntegrityCorePatch)

    execute {
        // 1. Suppress cached/bundled "Other Themes" (`loadDataFromLocal$2`) and online theme payloads
        //    (`loadDataFromServer$2`). Returning `null` causes `ThemeListViewModel` to populate only
        //    Row 1 (`Adaptive | Light Theme | Dark Theme`) in its mutable `ResourceFlow`.
        val loadLocalClass = ThemeLoadFromLocalFingerprint.classDef
        loadLocalClass.accessFlags = loadLocalClass.accessFlags or AccessFlags.PUBLIC.value
        loadLocalClass.returnNull(ThemeLoadFromLocalFingerprint.method)

        val loadServerClass = ThemeLoadFromServerFingerprint.classDef
        loadServerClass.accessFlags = loadServerClass.accessFlags or AccessFlags.PUBLIC.value
        loadServerClass.returnNull(ThemeLoadFromServerFingerprint.method)

        // 2. Ensure `ThemeListActivity` always appends the 31-swatch `"Local Classic Theme"` card
        //    (both online and offline) without showing any `"No internet connection"` banner.
        ensureClassicThemesAlwaysAvailable()
    }
}
