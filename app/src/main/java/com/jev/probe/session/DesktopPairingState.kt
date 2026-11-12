package com.jev.probe.session

/**
 * Process-local association of Android profile, verified desktop account,
 * and the specific pipeline that established the pairing.
 *
 * The actual authorization remains the user's explicit opt-in plus the
 * authenticated desktop relay; this map is not a pairing protocol.
 */
object DesktopPairingState {
    private data class Link(val accountRef: String, val ownerId: String)
    private val links = LinkedHashMap<String, Link>()

    @Synchronized
    fun establishAfterVerifiedPairing(
        androidProfileScope: String,
        desktopAccountRef: String,
        ownerId: String = "legacy"
    ): Boolean {
        if (!androidProfileScope.startsWith("com.tencent.mm@") ||
            desktopAccountRef.isBlank() || desktopAccountRef.length > 200 ||
            ownerId.isBlank()) return false
        links[androidProfileScope] = Link(desktopAccountRef, ownerId)
        return true
    }

    @Synchronized
    fun accountFor(androidProfileScope: String): String? = links[androidProfileScope]?.accountRef

    /** Stopping an old poller must not revoke a replacement poller. */
    @Synchronized
    fun revokeIfOwned(androidProfileScope: String, ownerId: String): Boolean {
        if (ownerId.isBlank() || links[androidProfileScope]?.ownerId != ownerId) return false
        links.remove(androidProfileScope)
        return true
    }

    @Synchronized
    fun revoke(androidProfileScope: String) {
        links.remove(androidProfileScope)
    }

    @Synchronized
    fun clear() = links.clear()
}
