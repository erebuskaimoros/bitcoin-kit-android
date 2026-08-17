package io.horizontalsystems.bitcoincore.transactions

/**
 * Controls what happens after an outbound transaction exhausts its bounded
 * broadcast attempts without an authoritative relay/rejection result.
 */
enum class BroadcastFailurePolicy {
    /** Historical BitcoinKit behavior: invalidate the transaction and unlock its inputs. */
    InvalidateAfterRetries,

    /** Preserve the exact pending transaction and keep its inputs locked. */
    RetainPending,
}
