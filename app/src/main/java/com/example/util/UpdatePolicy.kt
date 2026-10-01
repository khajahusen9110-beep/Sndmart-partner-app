package com.example.util

/** What the app must do about the row in `app_versions` for platform `delivery_partner_app`. */
enum class UpdateDecision {
    /** Installed build is current. */
    NONE,

    /** A newer build exists; show a dismissible "Update available" prompt. */
    OPTIONAL,

    /** Block the app until the partner updates. */
    REQUIRED
}

/** Compares dotted versions numerically ("1.10.0" > "1.9.2"); missing parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size, 3)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) return diff
    }
    return 0
}

/**
 * - Below `minimum_supported_version` -> REQUIRED.
 * - `force_update = true` and below `latest_version` -> REQUIRED. Partners already on the
 *   latest build are never blocked, even while the flag stays on.
 * - Below `latest_version` -> OPTIONAL.
 */
fun decideUpdate(
    installedVersion: String,
    minimumSupportedVersion: String?,
    latestVersion: String?,
    forceUpdate: Boolean
): UpdateDecision {
    val belowMinimum = !minimumSupportedVersion.isNullOrBlank() &&
        compareVersions(installedVersion, minimumSupportedVersion) < 0
    val belowLatest = !latestVersion.isNullOrBlank() &&
        compareVersions(installedVersion, latestVersion) < 0
    return when {
        belowMinimum -> UpdateDecision.REQUIRED
        forceUpdate && belowLatest -> UpdateDecision.REQUIRED
        belowLatest -> UpdateDecision.OPTIONAL
        else -> UpdateDecision.NONE
    }
}
