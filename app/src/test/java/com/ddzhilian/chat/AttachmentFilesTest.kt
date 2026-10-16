package com.ddzhilian.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the download-file renaming contract: existing files are never overwritten, so a repeated
 * download lands as `a.pdf`, `a (1).pdf`, `a (2).pdf`, …
 */
class AttachmentFilesTest {
    private fun name(original: String, existing: Set<String>) =
        AttachmentFiles.deduplicatedName(original) { it in existing }

    @Test fun keepsTheOriginalNameWhenItIsFree() {
        assertEquals("a.pdf", name("a.pdf", emptySet()))
        assertEquals("附件", name("附件", emptySet()))
    }

    @Test fun appendsTheFirstFreeIndexBeforeTheExtension() {
        assertEquals("a (1).pdf", name("a.pdf", setOf("a.pdf")))
        assertEquals("a (2).pdf", name("a.pdf", setOf("a.pdf", "a (1).pdf")))
        assertEquals("a (3).pdf", name("a.pdf", setOf("a.pdf", "a (1).pdf", "a (2).pdf")))
    }

    @Test fun reusesTheLowestFreeIndexAfterADeletion() {
        assertEquals("a (1).pdf", name("a.pdf", setOf("a.pdf", "a (2).pdf")))
    }

    @Test fun onlyTheLastDotSeparatesTheExtension() {
        assertEquals("photo.tar (1).gz", name("photo.tar.gz", setOf("photo.tar.gz")))
    }

    @Test fun namesWithoutAnExtensionKeepTheIndexAtTheEnd() {
        assertEquals("附件 (1)", name("附件", setOf("附件")))
    }

    @Test fun archiveQueriesAcceptBothMediaStoreRelativePaths() {
        assertEquals("Download/点点直连", RELATIVE_PATH)
        assertEquals("Download/点点直连/", RELATIVE_PATH_TRAILING)
    }
}
