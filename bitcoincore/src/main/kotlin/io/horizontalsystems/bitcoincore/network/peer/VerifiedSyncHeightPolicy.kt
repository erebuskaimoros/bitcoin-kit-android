package io.horizontalsystems.bitcoincore.network.peer

/** A floor below which a P2P peer/tip cannot be treated as verified sync. */
class VerifiedSyncHeightPolicy(
    val minimumVerifiedBlockHeight: Int = 0,
) {
    init {
        require(minimumVerifiedBlockHeight >= 0)
    }

    fun acceptsPeerHeight(announcedHeight: Int, localHeight: Int): Boolean =
        announcedHeight > 0 &&
            announcedHeight >= localHeight &&
            announcedHeight >= minimumVerifiedBlockHeight

    fun canFinish(announcedHeight: Int, downloadedHeight: Int): Boolean =
        announcedHeight >= minimumVerifiedBlockHeight &&
            downloadedHeight >= announcedHeight
}
