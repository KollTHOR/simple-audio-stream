package com.example.audiostreamer.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSigningCertificateVerifierTest {

    @Test
    fun matchesInstalledSigner_acceptsSameSigningIdentity() {
        val signer = setOf("installed-certificate")

        assertTrue(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = signer,
                archiveSigningHistory = signer,
                installedCurrentSigners = signer
            )
        )
    }

    @Test
    fun matchesInstalledSigner_acceptsForwardRotationWithInstalledSignerInVerifiedHistory() {
        assertTrue(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = setOf("new-certificate"),
                archiveSigningHistory = setOf("old-certificate", "new-certificate"),
                installedCurrentSigners = setOf("old-certificate")
            )
        )
    }

    @Test
    fun matchesInstalledSigner_rejectsUnrelatedOrReverseSigner() {
        assertFalse(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = setOf("attacker-certificate"),
                archiveSigningHistory = setOf("attacker-certificate"),
                installedCurrentSigners = setOf("installed-certificate")
            )
        )
        assertFalse(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = setOf("old-certificate"),
                archiveSigningHistory = setOf("old-certificate"),
                installedCurrentSigners = setOf("new-certificate")
            )
        )
    }

    @Test
    fun matchesInstalledSigner_rejectsMissingAndMismatchedMultiSignerIdentities() {
        assertFalse(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = emptySet(),
                archiveSigningHistory = emptySet(),
                installedCurrentSigners = setOf("installed-certificate")
            )
        )
        assertFalse(
            ApkSigningCertificateVerifier.matchesInstalledSigner(
                archiveCurrentSigners = setOf("archive-a", "archive-b"),
                archiveSigningHistory = setOf("archive-a", "archive-b"),
                installedCurrentSigners = setOf("installed-a", "installed-b")
            )
        )
    }
}
