package com.binteum.app.nearby

/** Workaround for the observed blank/black custom-permission UI on Connect 2.1 emulator only. */
object LocationPermissionPolicy {
    fun hasBrokenEmulatorDialog(fingerprint: String, permissionGroup: String?): Boolean =
        fingerprint.startsWith("Pleos/crp_avd_") && fingerprint.contains("release_connect_v2.1.0") &&
            permissionGroup.isNullOrBlank()
}
