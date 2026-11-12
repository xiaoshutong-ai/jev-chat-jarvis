package com.jev.probe.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrAnalysisScopeTest {
    @Test fun manualScreenshotWithoutPackageKeepsExplicitEmptyScope() {
        val source = OcrAnalysisScope.nextActivePackage(null, "", manual = true)
        // The foreground window may have another package name, but it must not
        // replace the capture session's explicit unknown-package scope.
        assertEquals("", source)
        assertEquals("", source ?: "com.tencent.mm")
        assertEquals(source, source ?: "")
    }

    @Test fun manualScreenshotClearsStaleChatPackage() {
        assertEquals(
            "",
            OcrAnalysisScope.nextActivePackage(
                "com.tencent.mobileqq", "", manual = true
            )
        )
    }

    @Test fun manualCloneUserScreenshotRetainsItsExplicitSource() {
        assertEquals(
            "com.tencent.mm",
            OcrAnalysisScope.nextActivePackage(null, "com.tencent.mm", manual = true)
        )
    }

    @Test fun automaticScreenshotRetainsOrUpdatesItsSource() {
        assertEquals(
            "com.ss.android.lark",
            OcrAnalysisScope.nextActivePackage(
                "com.ss.android.lark", "", manual = false
            )
        )
        assertEquals(
            "com.twitter.android",
            OcrAnalysisScope.nextActivePackage(
                "com.ss.android.lark", "com.twitter.android", manual = false
            )
        )
    }
}
