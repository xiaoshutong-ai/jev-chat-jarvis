package com.jev.probe.session

/**
 * Process-local association of the current Android profile and a verified,
 * opted-in desktop account. This is NOT a pairing protocol or proof of identity:
 * its single writer must be a future authenticated desktop-link handshake.
 *
 * Until such a handshake exists, desktop candidates cannot appear in the IME.
 */
object DesktopPairingState {
    private val links = LinkedHashMap<String, String>()

    @Synchronized
    fun establishAfterVerifiedPairing(androidProfileScope: String, desktopAccountRef: String): Boolean {
        if (!androidProfileScope.startsWith("com.tencent.mm@") ||
            desktopAccountRef.isBlank() || desktopAccountRef.length > 200) return false
        links[androidProfileScope] = desktopAccountRef
        return true
    }

    @Synchronized
    fun accountFor(androidProfileScope: String): String? = links[androidProfileScope]

    @Synchronized
    fun revoke(androidProfileScope: String) {
        links.remove(androidProfileScope)
    }

    @Synchronized
    fun clear() = links.clear()
}
