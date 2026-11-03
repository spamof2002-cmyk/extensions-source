import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Kens Scans"
    pkgName = "en.kensscancustom"
    versionCode = 12
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "Kens Scans"
        // Preserve the existing custom source ID, not the official Kensscan ID.
        id = 0x5f396eac1f6a2a20L
        lang = "en"
        baseUrl = "https://kensscan.com"
    }
}
