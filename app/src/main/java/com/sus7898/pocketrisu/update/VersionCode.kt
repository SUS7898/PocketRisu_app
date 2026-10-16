package com.sus7898.pocketrisu.update

/**
 * Mirror of versionCodeFrom() in app/build.gradle.kts and release.yml — keep the three in sync.
 *   1.2.3            -> 1_020_300
 *   1.2.3-dev.7+sha  -> 1_020_307
 */
object VersionCode {
    private val dev = Regex("""-dev\.(\d+)""")

    fun from(versionName: String): Int {
        val name = versionName.trim().removePrefix("v")
        val core = name.substringBefore('-').substringBefore('+').split('.')
        val major = core.getOrNull(0)?.toIntOrNull() ?: return 0
        val minor = core.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = core.getOrNull(2)?.toIntOrNull() ?: 0
        val devN = dev.find(name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 0
        return major * 1_000_000 + minor * 10_000 + patch * 100 + devN
    }
}
