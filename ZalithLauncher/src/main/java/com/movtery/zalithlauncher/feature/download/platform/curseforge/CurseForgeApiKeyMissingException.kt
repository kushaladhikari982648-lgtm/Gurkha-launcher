package com.movtery.zalithlauncher.feature.download.platform.curseforge

/**
 * Thrown instead of sending requests that CurseForge would reject: the build has no CurseForge API key
 * (see PlatformUtils.hasCurseForgeApiKey). The download pages show R.string.download_curseforge_no_api_key for it.
 */
class CurseForgeApiKeyMissingException :
    RuntimeException("This build of the launcher has no CurseForge API key, so CurseForge can't be used (Modrinth still works)")
