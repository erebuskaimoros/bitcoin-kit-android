package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.network.Network

class MainNetDogecoin : Network() {
    override val protocolVersion = 70015
    override var port = 22556
    override var magic = 0xc0c0c0c0L
    override var bip32HeaderPub = 0x02facafd
    override var bip32HeaderPriv = 0x02fac398
    override var addressVersion = 30
    override var addressSegwitHrp = ""
    override var addressScriptVersion = 22
    override var coinType = 3
    override val blockchairChainId = "dogecoin"
    override val maxBlockSize = 1_000_000
    override val dustRelayTxFee = 100_000
    override val syncableFromApi = true
    override var dnsSeeds = listOf("seed.multidoge.org", "seed2.multidoge.org")
}
