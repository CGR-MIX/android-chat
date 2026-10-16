package com.ddzhilian.chat

import org.junit.Assert.*
import org.junit.Test

class UpdateProtocolTest {
    /** 构造一个 pkt-line 行：4 位十六进制长度（含自身）+ 内容 + 换行。 */
    private fun pkt(text: String): String {
        val payload = text + "\n"
        return (payload.length + 4).toString(16).padStart(4, '0') + payload
    }

    /** 一份 GitHub 风格的 refs 广告：service 头、flush、带能力的 HEAD 行、分支、tag 与 peeled tag。 */
    private fun advertisement() = pkt("# service=git-upload-pack") + "0000" +
        pkt("1111111111111111111111111111111111111111 HEAD\u0000multi_ack thin-pack side-band-64k") +
        pkt("2222222222222222222222222222222222222222 refs/heads/main") +
        pkt("3333333333333333333333333333333333333333 refs/tags/v1.4.0") +
        pkt("4444444444444444444444444444444444444444 refs/tags/v1.5.0") +
        pkt("5555555555555555555555555555555555555555 refs/tags/v1.5.0^{}") +
        pkt("6666666666666666666666666666666666666666 refs/tags/v1.6.0-rc1") +
        pkt("7777777777777777777777777777777777777777 refs/tags/nightly") +
        "0000"

    @Test fun refsAdvertisementParsesRefsAndPeeledTags() {
        val refs = parseRefAdvertisement(advertisement())
        assertEquals(7, refs.size)
        assertEquals(GitRef("1111111111111111111111111111111111111111", "HEAD"), refs.first())
        assertEquals("HEAD", refs.first().name)
        assertTrue(refs.any { it.name == "refs/tags/v1.5.0^{}" })
    }

    @Test fun highestVersionTagWinsAndPrereleasesStayOutOfTheChannel() {
        val release = latestRelease(parseRefAdvertisement(advertisement()))
        assertEquals("v1.5.0", release?.tag)
        assertEquals(AppVersion(1, 5, 0, "v1.5.0"), release?.version)
        // 只有分支、没有版本 tag 的仓库不会被当成有更新。
        assertNull(latestRelease(parseRefAdvertisement(pkt("2222222222222222222222222222222222222222 refs/heads/main") + "0000")))
        assertNull(latestRelease(emptyList()))
    }

    @Test fun versionTagsAcceptPlausibleFormsAndRejectEverythingElse() {
        assertEquals(AppVersion(1, 4, 0, "1.4.0"), parseVersion("1.4.0"))
        assertEquals(AppVersion(1, 4, 0, "v1.4"), parseVersion("v1.4"))
        assertEquals(AppVersion(2, 0, 0, "V2"), parseVersion("V2"))
        assertEquals(AppVersion(1, 10, 3, "v1.10.3"), parseVersion(" v1.10.3 "))
        listOf("v1.6.0-rc1", "1.4.0+build7", "release", "", "1.4.0.1", "1.x.0", "v").forEach { raw ->
            assertNull(raw, parseVersion(raw))
        }
        assertEquals("v1.4.0", versionTagName("refs/tags/v1.4.0"))
        listOf("refs/heads/main", "refs/tags/v1.5.0^{}", "refs/tags/nightly", "refs/tags/", "HEAD").forEach { ref ->
            assertNull(ref, versionTagName(ref))
        }
    }

    @Test fun comparisonIsNumericNotLexicographic() {
        assertTrue(AppVersion(1, 10, 0, "1.10.0") > AppVersion(1, 9, 9, "1.9.9"))
        assertTrue(AppVersion(2, 0, 0, "2.0.0") > AppVersion(1, 99, 99, "1.99.99"))
        assertEquals(0, AppVersion(1, 4, 0, "V1.4").compareTo(AppVersion(1, 4, 0, "1.4.0")))
    }

    @Test fun updateAvailabilityComparesBuildVersionWithChannelTag() {
        assertTrue(isUpdateAvailable("1.4.0", AppVersion(1, 5, 0, "v1.5.0")))
        assertTrue(isUpdateAvailable("1.9.0", AppVersion(1, 10, 0, "v1.10.0")))
        assertFalse(isUpdateAvailable("1.4.0", AppVersion(1, 4, 0, "v1.4.0")))
        // 当前版本读不懂时不误报更新。
        assertFalse(isUpdateAvailable("dev", AppVersion(9, 0, 0, "v9.0.0")))
    }

    @Test fun apkUrlsFallBackFromReleaseAssetToTaggedBuildOutput() {
        val release = UpdateRelease("v1.5.0", AppVersion(1, 5, 0, "v1.5.0"))
        assertEquals(
            "https://github.com/CGR-MIX/android-chat/releases/download/v1.5.0/ddzhilian-chat-debug.apk",
            release.apkUrls[0],
        )
        assertEquals(
            "https://raw.githubusercontent.com/CGR-MIX/android-chat/v1.5.0/output/ddzhilian-chat-debug.apk",
            release.apkUrls[1],
        )
        assertEquals("https://github.com/CGR-MIX/android-chat.git/info/refs?service=git-upload-pack", UPDATE_REFS_URL)
        assertEquals("https://github.com/CGR-MIX/android-chat/releases/tag/v1.5.0", release.pageUrl)
    }
}
