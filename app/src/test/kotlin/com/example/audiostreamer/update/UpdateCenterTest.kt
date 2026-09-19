package com.example.audiostreamer.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateCenterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. RELEASE JSON PARSING
    // =========================================================================

    @Test
    fun parseStableReleaseJson_withValidApkAndChecksum() {
        val jsonStr = """
        {
            "id": 1001,
            "tag_name": "v1.8.14",
            "name": "Simple Audio Stream 1.8.14",
            "body": "Official stable release.\nversionCode: 109\ncommit: 3cd11fe3b1a2",
            "published_at": "2026-09-16T12:00:00Z",
            "prerelease": false,
            "draft": false,
            "html_url": "https://github.com/KollTHOR/simple-audio-stream/releases/tag/v1.8.14",
            "assets": [
                {
                    "id": 201,
                    "name": "app-release.apk",
                    "browser_download_url": "https://github.com/KollTHOR/simple-audio-stream/releases/download/v1.8.14/app-release.apk",
                    "size": 5242880
                },
                {
                    "id": 202,
                    "name": "app-release.apk.sha256",
                    "browser_download_url": "https://github.com/KollTHOR/simple-audio-stream/releases/download/v1.8.14/app-release.apk.sha256",
                    "size": 95
                }
            ]
        }
        """.trimIndent()

        val release = GithubRelease.fromJson(JSONObject(jsonStr))

        assertEquals(1001L, release.id)
        assertEquals("v1.8.14", release.tagName)
        assertEquals("1.8.14", release.cleanVersion)
        assertEquals(ReleaseChannel.STABLE, release.channel)
        assertFalse(release.isPrerelease)
        assertEquals("3cd11fe", release.commitSha)
        assertEquals("3cd11fe", release.commitShort)
        assertEquals(109L, release.parsedVersionCode)
        assertEquals("2026-09-16", release.formattedPublishDate)
        assertEquals("5.0 MB", release.formattedSize)

        assertNotNull(release.apkAsset)
        assertEquals("app-release.apk", release.apkAsset?.name)
        assertEquals(5242880L, release.apkAsset?.sizeBytes)
        assertTrue(release.apkAsset!!.isApk)
        assertFalse(release.apkAsset!!.isSha256)

        assertNotNull(release.checksumAsset)
        assertEquals("app-release.apk.sha256", release.checksumAsset?.name)
        assertTrue(release.checksumAsset!!.isSha256)
        assertFalse(release.checksumAsset!!.isApk)
    }

    @Test
    fun parseNightlyReleaseJson_withPrereleaseFlagAndNightlyTag() {
        val jsonStr = """
        {
            "id": 1002,
            "tag_name": "nightly-20260916-a1b2c3d",
            "name": "Nightly Build 2026-09-16",
            "body": "Automated nightly build.\nBuild 110\ncommit=a1b2c3d4e5",
            "published_at": "2026-09-16T18:30:00Z",
            "prerelease": true,
            "draft": false,
            "html_url": "https://github.com/KollTHOR/simple-audio-stream/releases/tag/nightly-20260916-a1b2c3d",
            "assets": [
                {
                    "id": 301,
                    "name": "app-nightly.apk",
                    "browser_download_url": "https://example.com/nightly.apk",
                    "size": 5500000
                },
                {
                    "id": 302,
                    "name": "app-nightly.apk.sha256",
                    "browser_download_url": "https://example.com/nightly.apk.sha256",
                    "size": 95
                }
            ]
        }
        """.trimIndent()

        val release = GithubRelease.fromJson(JSONObject(jsonStr))

        assertEquals(1002L, release.id)
        assertEquals(ReleaseChannel.NIGHTLY, release.channel)
        assertTrue(release.isPrerelease)
        assertEquals("a1b2c3d", release.commitSha)
        assertEquals(110L, release.parsedVersionCode)
        assertEquals("2026-09-16", release.formattedPublishDate)
        assertNotNull(release.apkAsset)
        assertNotNull(release.checksumAsset)
    }

    @Test
    fun parseNightlyReleaseJson_withBuildNumberInTag() {
        val jsonStr = """
        {
            "id": 1005,
            "tag_name": "nightly-20260919-b129-054ad38",
            "name": "Nightly Build: nightly-20260919-b129-054ad38",
            "body": "Automated build without body metadata",
            "published_at": "2026-09-19T12:00:00Z",
            "prerelease": true,
            "draft": false,
            "html_url": "https://github.com/KollTHOR/simple-audio-stream/releases/tag/nightly-20260919-b129-054ad38",
            "assets": [
                {
                    "id": 501,
                    "name": "SimpleAudioStream-1.8.15-nightly.20260919+054ad38.apk",
                    "browser_download_url": "https://example.com/nightly.apk",
                    "size": 5500000
                }
            ]
        }
        """.trimIndent()

        val release = GithubRelease.fromJson(JSONObject(jsonStr))

        assertEquals(1005L, release.id)
        assertEquals(ReleaseChannel.NIGHTLY, release.channel)
        assertTrue(release.isPrerelease)
        assertEquals("054ad38", release.commitSha)
        assertEquals(129L, release.parsedVersionCode)
        assertEquals("2026-09-19", release.formattedPublishDate)
        assertNotNull(release.apkAsset)
    }

    @Test
    fun parseRelease_missingApkAsset() {
        val jsonStr = """
        {
            "id": 1003,
            "tag_name": "v1.8.14",
            "name": "Source only release",
            "body": "No binary assets attached",
            "published_at": "2026-09-16T00:00:00Z",
            "prerelease": false,
            "draft": false,
            "assets": [
                {
                    "id": 401,
                    "name": "source.tar.gz",
                    "browser_download_url": "https://example.com/source.tar.gz",
                    "size": 1000
                }
            ]
        }
        """.trimIndent()

        val release = GithubRelease.fromJson(JSONObject(jsonStr))
        assertNull(release.apkAsset)
        assertNull(release.checksumAsset)
        assertEquals("No APK", release.formattedSize)
    }

    @Test
    fun parseRelease_missingChecksumAsset() {
        val jsonStr = """
        {
            "id": 1004,
            "tag_name": "v1.8.14",
            "name": "APK without checksum",
            "body": "No sha256 checksum",
            "published_at": "2026-09-16T00:00:00Z",
            "prerelease": false,
            "draft": false,
            "assets": [
                {
                    "id": 501,
                    "name": "simple-stream.apk",
                    "browser_download_url": "https://example.com/simple-stream.apk",
                    "size": 4000000
                }
            ]
        }
        """.trimIndent()

        val release = GithubRelease.fromJson(JSONObject(jsonStr))
        assertNotNull(release.apkAsset)
        assertNull(release.checksumAsset)
    }

    @Test
    fun parseList_ignoresDraftReleasesAndEmptyTags() {
        val jsonArrayStr = """
        [
            {
                "id": 1,
                "tag_name": "v1.8.14",
                "draft": false
            },
            {
                "id": 2,
                "tag_name": "v1.8.15-draft",
                "draft": true
            },
            {
                "id": 3,
                "tag_name": "",
                "draft": false
            }
        ]
        """.trimIndent()

        val list = GithubRelease.parseList(JSONArray(jsonArrayStr))
        assertEquals(1, list.size)
        assertEquals(1L, list[0].id)
    }

    // =========================================================================
    // 2. VERSION COMPARATOR
    // =========================================================================

    @Test
    fun versionComparator_comparesByVersionCodeAccurately() {
        val installed = BuildInfo(
            versionName = "1.8.14",
            versionCode = 109L,
            channel = ReleaseChannel.STABLE,
            gitCommitSha = "3cd11fe",
            buildTimestamp = "20260916",
            baseVersionName = "1.8.14"
        )

        // Newer version code (upgrade)
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(110L, "1.8.15", installed)
        )

        // Same version code
        assertEquals(
            UpdateCompatibility.SAME,
            VersionComparator.compare(109L, "1.8.14", installed)
        )

        // Older version code (rollback candidate)
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(67L, "1.8.13", installed)
        )
    }

    @Test
    fun versionComparator_semanticVersionFallback() {
        val installed = BuildInfo(
            versionName = "1.8.14",
            versionCode = 109L,
            channel = ReleaseChannel.STABLE,
            gitCommitSha = "3cd11fe",
            buildTimestamp = "20260916",
            baseVersionName = "1.8.14"
        )

        // Higher major version
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "2.0.0", installed)
        )

        // Higher minor version
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "1.9.0", installed)
        )

        // Higher patch version
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "1.8.15", installed)
        )

        // Same semantic version
        assertEquals(
            UpdateCompatibility.SAME,
            VersionComparator.compare(null, "1.8.14", installed)
        )

        // Older patch version (rollback)
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(null, "1.8.13", installed)
        )

        // Older minor version (rollback)
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(null, "1.7.0", installed)
        )
    }

    @Test
    fun versionComparator_stableVsNightly() {
        // A stable release 1.8.14 is considered newer than a nightly prerelease 1.8.14-nightly.20260916
        val cmp = VersionComparator.compareSemantic("1.8.14", "1.8.14-nightly.20260916")
        assertTrue("Stable 1.8.14 should be greater than nightly 1.8.14", cmp > 0)

        val cmpInverse = VersionComparator.compareSemantic("1.8.14-nightly.20260916", "1.8.14")
        assertTrue("Nightly 1.8.14 should be less than stable 1.8.14", cmpInverse < 0)
    }

    @Test
    fun versionComparator_nightlyVsNewerNightly() {
        val vOld = "1.8.14-nightly.20260915"
        val vNew = "1.8.14-nightly.20260916"

        assertTrue("vNew should be greater than vOld", VersionComparator.compareSemantic(vNew, vOld) > 0)
        assertTrue("vOld should be less than vNew", VersionComparator.compareSemantic(vOld, vNew) < 0)
    }

    @Test
    fun versionComparator_dateTaggedNightlyVsSemanticNightly() {
        val installed = BuildInfo(
            versionName = "1.8.14-nightly.20260916+11a0789",
            versionCode = 111L,
            channel = ReleaseChannel.NIGHTLY,
            gitCommitSha = "11a0789",
            buildTimestamp = "20260916",
            baseVersionName = "1.8.14"
        )

        // Date-formatted nightly tag without leading major.minor is recognized as NEWER based on build date
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "nightly-20260917-ec5fbe3", installed)
        )
    }

    @Test
    fun versionComparator_nightlyWithOlderVersionCodeButNewerDateIsNewer() {
        val installed = BuildInfo(
            versionName = "1.8.15-nightly.20260917+ec5fbe3",
            versionCode = 113L,
            channel = ReleaseChannel.NIGHTLY,
            gitCommitSha = "ec5fbe3",
            buildTimestamp = "20260917",
            baseVersionName = "1.8.15"
        )

        // Remote candidate has an erroneous lower versionCode (e.g. 112) but newer date (20260919)
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(112L, "nightly-20260919-0b8352a", installed)
        )
    }

    @Test
    fun versionComparator_oldStableToNewStableIsNewer() {
        val installed = BuildInfo(
            versionName = "1.8.12",
            versionCode = 65L,
            channel = ReleaseChannel.STABLE,
            gitCommitSha = "a6c1ebd",
            buildTimestamp = "20260916",
            baseVersionName = "1.8.12"
        )

        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(119L, "1.8.15", installed)
        )
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "1.8.15", installed)
        )
    }

    @Test
    fun versionComparator_nightlyToStableTransitionIsNewer() {
        val installed = BuildInfo(
            versionName = "1.8.15-nightly.20260919+0b8352a",
            versionCode = 118L,
            channel = ReleaseChannel.NIGHTLY,
            gitCommitSha = "0b8352a",
            buildTimestamp = "20260919",
            baseVersionName = "1.8.15"
        )

        // Same release line final stable is newer than its nightly
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(119L, "1.8.15", installed)
        )
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "1.8.15", installed)
        )

        // Next release line stable is also newer
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(120L, "1.9.0", installed)
        )
        assertEquals(
            UpdateCompatibility.NEWER,
            VersionComparator.compare(null, "1.9.0", installed)
        )
    }

    @Test
    fun versionComparator_oldStableCannotReplaceNewerNightly() {
        val installed = BuildInfo(
            versionName = "1.8.15-nightly.20260919+0b8352a",
            versionCode = 118L,
            channel = ReleaseChannel.NIGHTLY,
            gitCommitSha = "0b8352a",
            buildTimestamp = "20260919",
            baseVersionName = "1.8.15"
        )

        // Older stable v1.8.12 must be classified as OLDER (Rollback), never NEWER
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(65L, "1.8.12", installed)
        )
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(null, "1.8.12", installed)
        )
    }

    @Test
    fun versionComparator_nightlyCannotReplaceNewerStable() {
        val installed = BuildInfo(
            versionName = "1.9.0",
            versionCode = 120L,
            channel = ReleaseChannel.STABLE,
            gitCommitSha = "abcdef1",
            buildTimestamp = "20260920",
            baseVersionName = "1.9.0"
        )

        // Older line nightly must be OLDER
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(118L, "1.8.15-nightly.20260919+0b8352a", installed)
        )
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(null, "1.8.15-nightly.20260919+0b8352a", installed)
        )

        // Same line nightly must also be OLDER than final stable
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(119L, "1.9.0-nightly.20260919+abcdef0", installed)
        )
        assertEquals(
            UpdateCompatibility.OLDER,
            VersionComparator.compare(null, "1.9.0-nightly.20260919+abcdef0", installed)
        )
    }

    // =========================================================================
    // 3. CHECKSUM VERIFIER
    // =========================================================================

    @Test
    fun checksumVerifier_calculatesSha256Correctly() {
        val testFile = tempFolder.newFile("test_payload.bin")
        testFile.writeText("SimpleAudioStreamIntegrityCheck")

        // Expected SHA-256 of "SimpleAudioStreamIntegrityCheck"
        val expectedSha = "16d717934a553e7106bb006bb08eb0463bf25035990740bfe20c7d693f612b54"
        val actualSha = ChecksumVerifier.calculateSha256(testFile)

        assertEquals(expectedSha, actualSha)
    }

    @Test
    fun checksumVerifier_verifiesValidAndInvalidChecksums() {
        val testFile = tempFolder.newFile("test_apk.apk")
        testFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val actualSha = ChecksumVerifier.calculateSha256(testFile)

        // Raw hash verification
        assertTrue(ChecksumVerifier.verify(testFile, actualSha))

        // Uppercase hash verification
        assertTrue(ChecksumVerifier.verify(testFile, actualSha.uppercase()))

        // sha256sum output format: "hash  filename"
        val shaSumFormat = "$actualSha  app-release.apk\n"
        assertTrue(ChecksumVerifier.verify(testFile, shaSumFormat))

        // Incorrect hash verification
        val corruptSha = "0000000000000000000000000000000000000000000000000000000000000000"
        assertFalse(ChecksumVerifier.verify(testFile, corruptSha))

        // Malformed hash
        assertFalse(ChecksumVerifier.verify(testFile, "not-a-sha-256"))
    }

    @Test
    fun checksumVerifier_parseExpectedHash() {
        val validHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        assertEquals(validHash, ChecksumVerifier.parseExpectedHash(validHash))
        assertEquals(validHash, ChecksumVerifier.parseExpectedHash("$validHash  app-release.apk"))
        assertEquals(validHash, ChecksumVerifier.parseExpectedHash("  $validHash \n"))
        assertEquals(validHash, ChecksumVerifier.parseExpectedHash(validHash.uppercase()))

        assertNull(ChecksumVerifier.parseExpectedHash("too_short_hash_12345"))
        assertNull(ChecksumVerifier.parseExpectedHash(""))
    }

    // =========================================================================
    // 4. BUILD INFO
    // =========================================================================

    @Test
    fun buildInfo_formattingAndCurrent() {
        val buildInfo = BuildInfo(
            versionName = "1.8.14-nightly.20260916+3cd11fe",
            versionCode = 109L,
            channel = ReleaseChannel.NIGHTLY,
            gitCommitSha = "3cd11fe3b1a2",
            buildTimestamp = "20260916",
            baseVersionName = "1.8.14"
        )

        assertEquals("Version 1.8.14-nightly.20260916+3cd11fe (Build 109)", buildInfo.displayVersion)
        assertEquals("3cd11fe", buildInfo.commitShort)
        assertEquals("2026-09-16", buildInfo.formattedBuildDate)
        assertEquals(ReleaseChannel.NIGHTLY, buildInfo.channel)

        val current = BuildInfo.current()
        assertNotNull(current.versionName)
        assertTrue(current.versionCode > 0)
        assertNotNull(current.channel)
        assertNotNull(current.gitCommitSha)
    }

    // =========================================================================
    // 5. UPDATE REPOSITORY FILTERING & CANDIDATE SELECTION
    // =========================================================================

    @Test
    fun updateRepository_filterByChannel() {
        val stableRelease = GithubRelease(
            id = 1,
            tagName = "v1.8.14",
            name = "Stable 1.8.14",
            body = "",
            publishedAt = "2026-09-16T12:00:00Z",
            isPrerelease = false,
            htmlUrl = "",
            apkAsset = ReleaseAsset(1, "app.apk", "http://dl/apk", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.STABLE,
            parsedVersionName = "1.8.14",
            parsedVersionCode = 109L,
            commitSha = "3cd11fe"
        )

        val nightlyRelease = GithubRelease(
            id = 2,
            tagName = "nightly-20260916-3cd11fe",
            name = "Nightly 2026-09-16",
            body = "",
            publishedAt = "2026-09-16T15:00:00Z",
            isPrerelease = true,
            htmlUrl = "",
            apkAsset = ReleaseAsset(2, "nightly.apk", "http://dl/nightly", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.NIGHTLY,
            parsedVersionName = "nightly-20260916",
            parsedVersionCode = 110L,
            commitSha = "3cd11fe"
        )

        val releases = listOf(nightlyRelease, stableRelease)

        // When user selects STABLE channel: nightly prereleases must be filtered out
        val stableOnly = UpdateRepository.filterByChannel(releases, ReleaseChannel.STABLE)
        assertEquals(1, stableOnly.size)
        assertEquals(ReleaseChannel.STABLE, stableOnly[0].channel)
        assertEquals("v1.8.14", stableOnly[0].tagName)

        // When user selects NIGHTLY channel: all releases (nightly + stable) should be available
        val nightlyAll = UpdateRepository.filterByChannel(releases, ReleaseChannel.NIGHTLY)
        assertEquals(2, nightlyAll.size)
    }

    @Test
    fun updateRepository_findLatestCandidate_skipsReleasesWithoutApk() {
        val releaseWithoutApk = GithubRelease(
            id = 1,
            tagName = "v1.8.15",
            name = "Release with no APK attached",
            body = "",
            publishedAt = "2026-09-17T12:00:00Z",
            isPrerelease = false,
            htmlUrl = "",
            apkAsset = null,
            checksumAsset = null,
            channel = ReleaseChannel.STABLE,
            parsedVersionName = "1.8.15",
            parsedVersionCode = 111L,
            commitSha = "aaa1111"
        )

        val validRelease = GithubRelease(
            id = 2,
            tagName = "v1.8.14",
            name = "Valid Release with APK",
            body = "",
            publishedAt = "2026-09-16T12:00:00Z",
            isPrerelease = false,
            htmlUrl = "",
            apkAsset = ReleaseAsset(2, "app.apk", "http://dl/apk", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.STABLE,
            parsedVersionName = "1.8.14",
            parsedVersionCode = 109L,
            commitSha = "3cd11fe"
        )

        val releases = listOf(releaseWithoutApk, validRelease)

        val candidate = UpdateRepository.findLatestCandidate(releases, ReleaseChannel.STABLE)
        assertNotNull(candidate)
        assertEquals(2L, candidate?.id)
        assertEquals("v1.8.14", candidate?.tagName)
    }

    @Test
    fun versionComparator_nightlyTagsWithBuildNumber() {
        // Tag with higher build number on same date is newer
        val vNew = "nightly-20260919-b130-1111111"
        val vOld = "nightly-20260919-b129-0000000"

        assertTrue("vNew (b130) should be greater than vOld (b129)", VersionComparator.compareSemantic(vNew, vOld) > 0)
        assertTrue("vOld (b129) should be less than vNew (b130)", VersionComparator.compareSemantic(vOld, vNew) < 0)
    }

    @Test
    fun updateRepository_sortReleasesDescending_sortsByBuildNumberAndVersion() {
        val rel126 = GithubRelease(
            id = 1,
            tagName = "nightly-20260919-8268319",
            name = "Nightly 126",
            body = "",
            publishedAt = "2026-09-19T10:00:00Z",
            isPrerelease = true,
            htmlUrl = "",
            apkAsset = ReleaseAsset(1, "app.apk", "http://dl/1", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.NIGHTLY,
            parsedVersionName = "1.8.15-nightly.20260919+8268319",
            parsedVersionCode = 126L,
            commitSha = "8268319"
        )

        val rel125 = GithubRelease(
            id = 2,
            tagName = "nightly-20260919-30d193e",
            name = "Nightly 125",
            body = "",
            publishedAt = "2026-09-19T09:00:00Z",
            isPrerelease = true,
            htmlUrl = "",
            apkAsset = ReleaseAsset(2, "app.apk", "http://dl/2", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.NIGHTLY,
            parsedVersionName = "1.8.15-nightly.20260919+30d193e",
            parsedVersionCode = 125L,
            commitSha = "30d193e"
        )

        val rel128 = GithubRelease(
            id = 3,
            tagName = "nightly-20260919-054ad38",
            name = "Nightly 128",
            body = "",
            publishedAt = "2026-09-19T11:00:00Z",
            isPrerelease = true,
            htmlUrl = "",
            apkAsset = ReleaseAsset(3, "app.apk", "http://dl/3", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.NIGHTLY,
            parsedVersionName = "1.8.15-nightly.20260919+054ad38",
            parsedVersionCode = 128L,
            commitSha = "054ad38"
        )

        val rel129 = GithubRelease(
            id = 4,
            tagName = "nightly-20260919-b129-abcdef0",
            name = "Nightly 129",
            body = "",
            publishedAt = "2026-09-19T12:00:00Z",
            isPrerelease = true,
            htmlUrl = "",
            apkAsset = ReleaseAsset(4, "app.apk", "http://dl/4", 5000, isApk = true, isSha256 = false),
            checksumAsset = null,
            channel = ReleaseChannel.NIGHTLY,
            parsedVersionName = "1.8.15-nightly.20260919+abcdef0",
            parsedVersionCode = 129L,
            commitSha = "abcdef0"
        )

        // Pass in GitHub's unsorted/inverted order (e.g. 126 first, then 125, then 128, then 129)
        val unsorted = listOf(rel126, rel125, rel128, rel129)

        val sorted = UpdateRepository.sortReleasesDescending(unsorted)
        assertEquals(4, sorted.size)
        assertEquals(129L, sorted[0].parsedVersionCode)
        assertEquals(128L, sorted[1].parsedVersionCode)
        assertEquals(126L, sorted[2].parsedVersionCode)
        assertEquals(125L, sorted[3].parsedVersionCode)

        // Candidate must be the latest (129)
        val candidate = UpdateRepository.findLatestCandidate(unsorted, ReleaseChannel.NIGHTLY)
        assertNotNull(candidate)
        assertEquals("nightly-20260919-b129-abcdef0", candidate?.tagName)
        assertEquals(129L, candidate?.parsedVersionCode)
    }
}
