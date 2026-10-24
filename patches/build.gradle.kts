group = "app.GinArena.onedm"

patches {
    about {
        name = "GinArena Patch"
        description = "Removes visible ads and promotions from free 1DM 20.0 (30257), including the downloads-home VPN promo bar and sponsored browser start-page tiles. Stock APKM: https://www.apkmirror.com/apk/Vicky%20Bonick/idm-download-video-movie-music-anime-torrent/1dm-browser-video-download-20-0-release/1dm-browser-video-download-20-0-2-android-apk-download/"
        source = "https://github.com/sagarkhansau19-svg/GinArena-patch"
        author = "sagarkhansau19-svg"
        contact = "https://github.com/sagarkhansau19-svg/GinArena-patch/issues"
        website = "https://github.com/sagarkhansau19-svg/GinArena-patch"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
