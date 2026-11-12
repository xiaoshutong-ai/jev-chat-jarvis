package com.jev.probe.capture

/**
 * A user-initiated screenshot is an explicit source even when its package is
 * unknown. The empty string is intentional: do not infer an identity from the
 * Accessibility tree of an unrelated/foreground app.
 */
internal object OcrAnalysisScope {
    fun nextActivePackage(previous: String?, capturePackage: String, manual: Boolean): String? =
        if (manual || capturePackage.isNotEmpty()) capturePackage else previous
}
