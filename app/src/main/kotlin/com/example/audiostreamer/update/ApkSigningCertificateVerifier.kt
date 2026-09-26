package com.example.audiostreamer.update

import java.security.MessageDigest

/** Verifies that an update is signed by the installed app's current signing identity. */
internal object ApkSigningCertificateVerifier {
    fun fingerprint(certificate: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate)
        .joinToString("") { "%02x".format(it) }

    /**
     * Allows an Android-verified signing-certificate rotation only when the downloaded APK's
     * signing history contains the currently installed signer. Multi-signer packages must match
     * exactly because Android does not support rotation for those identities.
     */
    fun matchesInstalledSigner(
        archiveCurrentSigners: Set<String>,
        archiveSigningHistory: Set<String>,
        installedCurrentSigners: Set<String>
    ): Boolean {
        if (archiveCurrentSigners.isEmpty() || installedCurrentSigners.isEmpty()) return false
        if (archiveCurrentSigners == installedCurrentSigners) return true

        return archiveCurrentSigners.size == 1 &&
            installedCurrentSigners.size == 1 &&
            archiveSigningHistory.containsAll(installedCurrentSigners)
    }
}
