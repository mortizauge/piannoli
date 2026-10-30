package dev.piannoli.scorza.updater

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {

    @Test
    fun `compares numeric version components rather than lexically`() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!! > ReleaseVersion.parse("1.9.9")!!)
        assertTrue(ReleaseVersion.parse("2.0.0")!! > ReleaseVersion.parse("1.99.99")!!)
        assertEquals(0, ReleaseVersion.parse("v1.2.3+build.4")!!.compareTo(ReleaseVersion.parse("1.2.3+build.5")!!))
    }

    @Test
    fun `orders semantic prereleases before stable versions`() {
        assertTrue(ReleaseVersion.parse("1.2.3-alpha.2")!! < ReleaseVersion.parse("1.2.3-alpha.10")!!)
        assertTrue(ReleaseVersion.parse("1.2.3-rc.1")!! < ReleaseVersion.parse("1.2.3")!!)
        assertNull(ReleaseVersion.parse("1.02.3"))
    }

    @Test
    fun `stable channel excludes prereleases and drafts while beta includes both published types`() {
        val releases = JSONArray(
            """[
              {"tag_name":"v2.0.0-beta.1","prerelease":true,"draft":false,"assets":[${asset("piannoli-beta.apk")}]},
              {"tag_name":"v1.9.0","prerelease":false,"draft":false,"assets":[${asset("piannoli-stable.apk")}]},
              {"tag_name":"v3.0.0","prerelease":false,"draft":true,"assets":[${asset("piannoli-draft.apk")}]}
            ]"""
        )

        assertEquals(
            listOf("1.9.0"),
            GitHubReleases.parsePage(releases, ReleaseChannel.STABLE, listOf("arm64-v8a")).map { it.versionName }
        )
        val beta = GitHubReleases.parsePage(releases, ReleaseChannel.BETA, listOf("arm64-v8a"))
        assertEquals(setOf("1.9.0", "2.0.0-beta.1"), beta.map { it.versionName }.toSet())
        assertTrue(beta.first { it.versionName.startsWith("2.") }.isPrerelease)
    }

    @Test
    fun `selects compatible APK and rejects unsafe or unrelated asset URLs`() {
        val releases = JSONArray(
            """[{"tag_name":"v1.2.0","prerelease":false,"draft":false,"assets":[
              ${asset("piannoli-1.2.0-arm64-v8a.apk")},
              ${asset("piannoli-1.2.0-x86_64.apk")},
              ${asset("../piannoli-1.2.0.apk")},
              ${asset("piannoli-1.2.0.apk", "https://attacker.example/file.apk")}
            ]}]"""
        )

        val selected = GitHubReleases.parsePage(
            releases,
            ReleaseChannel.STABLE,
            listOf("arm64-v8a", "armeabi-v7a")
        ).single()
        assertEquals("piannoli-1.2.0-arm64-v8a.apk", selected.apk)
        assertTrue(selected.downloadUrl.startsWith("https://github.com/mortizauge/piannoli/releases/download/"))
    }

    @Test
    fun `cached update must be newer and belong to this repo and channel`() {
        val info = UpdateInfo(
            versionName = "1.10.0",
            tag = "v1.10.0",
            apk = "piannoli-1.10.0.apk",
            downloadUrl = "https://github.com/mortizauge/piannoli/releases/download/v1.10.0/piannoli-1.10.0.apk",
            changelog = "",
            isPrerelease = false
        )
        assertTrue(
            GitHubReleases.isEligible(
                info,
                "1.9.0",
                ReleaseChannel.STABLE,
                GitHubReleases.SOURCE,
                ReleaseChannel.STABLE.name
            )
        )
        assertFalse(
            GitHubReleases.isEligible(
                info,
                "1.10.0",
                ReleaseChannel.STABLE,
                GitHubReleases.SOURCE,
                ReleaseChannel.STABLE.name
            )
        )
        assertFalse(
            GitHubReleases.isEligible(
                info,
                "1.9.0",
                ReleaseChannel.STABLE,
                "github.com/CannoliHQ/cannoli",
                ReleaseChannel.STABLE.name
            )
        )
        assertFalse(
            GitHubReleases.isEligible(
                info,
                "1.9.0",
                ReleaseChannel.STABLE,
                GitHubReleases.SOURCE,
                ReleaseChannel.BETA.name
            )
        )
    }

    @Test
    fun `test channel is removed and old saved value falls back to stable`() {
        assertEquals(listOf(ReleaseChannel.STABLE, ReleaseChannel.BETA), ReleaseChannel.entries)
        assertEquals(ReleaseChannel.STABLE, ReleaseChannel.fromString("TEST"))
        assertEquals(ReleaseChannel.BETA, ReleaseChannel.fromString("beta"))
    }

    private fun asset(name: String, url: String = downloadUrl(name)) =
        """{"name":"$name","browser_download_url":"$url"}"""

    private fun downloadUrl(name: String) =
        "https://github.com/mortizauge/piannoli/releases/download/v1.2.0/$name"
}
