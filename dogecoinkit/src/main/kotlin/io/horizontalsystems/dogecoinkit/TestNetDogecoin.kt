package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.network.Network

class TestNetDogecoin : Network() {
    override val protocolVersion = 70015
    override var port = 44556
    override var magic = 0xdcb7c1fcL
    override var bip32HeaderPub = 0x043587cf
    override var bip32HeaderPriv = 0x04358394
    override var addressVersion = 113
    override var addressSegwitHrp = ""
    override var addressScriptVersion = 196
    override var coinType = 1
    override val blockchairChainId = ""
    override val maxBlockSize = 1_000_000
    override val dustRelayTxFee = 100_000
    override val syncableFromApi = false
    override var dnsSeeds = listOf("testseed.jrn.me.uk")
}
