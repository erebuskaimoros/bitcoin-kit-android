package io.horizontalsystems.dogecoinkit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.horizontalsystems.bitcoincore.AbstractKit
import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.bitcoincore.BitcoinCoreBuilder
import io.horizontalsystems.bitcoincore.apisync.BCoinApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairApi
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairBlockHashFetcher
import io.horizontalsystems.bitcoincore.apisync.blockchair.BlockchairTransactionProvider
import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorSet
import io.horizontalsystems.bitcoincore.blocks.CumulativeWorkForkChoice
import io.horizontalsystems.bitcoincore.managers.ApiSyncStateManager
import io.horizontalsystems.bitcoincore.managers.Bip44RestoreKeyConverter
import io.horizontalsystems.bitcoincore.managers.BlockValidatorHelper
import io.horizontalsystems.bitcoincore.models.Address
import io.horizontalsystems.bitcoincore.models.Checkpoint
import io.horizontalsystems.bitcoincore.models.WatchAddressPublicKey
import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.storage.CoreDatabase
import io.horizontalsystems.bitcoincore.storage.Storage
import io.horizontalsystems.bitcoincore.transactions.BroadcastFailurePolicy
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.bitcoincore.utils.AddressConverterChain
import io.horizontalsystems.bitcoincore.utils.Base58AddressConverter
import io.horizontalsystems.bitcoincore.utils.PaymentAddressParser
import io.horizontalsystems.dogecoinkit.validators.DogecoinActivationValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinDifficultyValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinProofOfWorkValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinContextualValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinCheckpointValidator
import io.horizontalsystems.dogecoinkit.validators.DogecoinVersionActivations
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic

class DogecoinKit : AbstractKit {
    enum class NetworkType { MainNet, TestNet }
    enum class FullSyncStart { Bip44, RecentPinned }

    interface Listener : BitcoinCore.Listener

    override var bitcoinCore: BitcoinCore
    override var network: Network

    var listener: Listener? = null
        set(value) {
            field = value
            bitcoinCore.listener = value
        }

    constructor(
        context: Context,
        words: List<String>,
        passphrase: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        fullSyncStart: FullSyncStart = defaultFullSyncStart,
    ) : this(
        context,
        Mnemonic().toSeed(words, passphrase),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold,
        fullSyncStart,
    )

    constructor(
        context: Context,
        seed: ByteArray,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        fullSyncStart: FullSyncStart = defaultFullSyncStart,
    ) : this(
        context,
        HDExtendedKey(seed, Purpose.BIP44),
        walletId,
        networkType,
        peerSize,
        syncMode,
        confirmationsThreshold,
        fullSyncStart,
    )

    constructor(
        context: Context,
        extendedKey: HDExtendedKey,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        fullSyncStart: FullSyncStart = defaultFullSyncStart,
    ) {
        network = network(networkType)
        bitcoinCore = bitcoinCore(
            context,
            extendedKey,
            null,
            walletId,
            networkType,
            peerSize,
            syncMode,
            confirmationsThreshold,
            fullSyncStart,
        )
    }

    constructor(
        context: Context,
        watchAddress: String,
        walletId: String,
        networkType: NetworkType = defaultNetworkType,
        peerSize: Int = defaultPeerSize,
        syncMode: SyncMode = defaultSyncMode,
        confirmationsThreshold: Int = defaultConfirmationsThreshold,
        fullSyncStart: FullSyncStart = defaultFullSyncStart,
    ) {
        network = network(networkType)
        val address = parseAddress(watchAddress, network)
        require(address.scriptType == ScriptType.P2PKH) { "Dogecoin watch wallets support P2PKH only" }
        val watchKey = WatchAddressPublicKey(address.lockingScriptPayload, address.scriptType)
        bitcoinCore = bitcoinCore(
            context,
            null,
            watchKey,
            walletId,
            networkType,
            peerSize,
            syncMode,
            confirmationsThreshold,
            fullSyncStart,
        )
    }

    private fun bitcoinCore(
        context: Context,
        extendedKey: HDExtendedKey?,
        watchAddressPublicKey: WatchAddressPublicKey?,
        walletId: String,
        networkType: NetworkType,
        peerSize: Int,
        syncMode: SyncMode,
        confirmationsThreshold: Int,
        fullSyncStart: FullSyncStart,
    ): BitcoinCore {
        val database = CoreDatabase.getInstance(
            context,
            databaseName(networkType, walletId, syncMode, fullSyncStart),
        )
        val storage = Storage(database)
        val checkpoint = resolveCheckpoint(syncMode, network, storage, fullSyncStart)
        val apiState = ApiSyncStateManager(storage, network.syncableFromApi && syncMode !is SyncMode.Full)
        val blockchairApi = BlockchairApi(network.blockchairChainId)
        val apiProvider = when (networkType) {
            NetworkType.MainNet -> BlockchairTransactionProvider(
                blockchairApi,
                BlockchairBlockHashFetcher(blockchairApi),
            )
            NetworkType.TestNet -> BCoinApi("")
        }
        val headerParser = DogecoinBlockHeaderParser(strictChainId = networkType == NetworkType.MainNet)

        val builder = BitcoinCoreBuilder()
            .setContext(context)
            .setExtendedKey(extendedKey)
            .setWatchAddressPublicKey(watchAddressPublicKey)
            .setPurpose(Purpose.BIP44)
            .setNetwork(network)
            .setCheckpoint(checkpoint)
            .setPaymentAddressParser(PaymentAddressParser("dogecoin", removeScheme = true))
            .setPeerSize(peerSize)
            .setSyncMode(syncMode)
            .setConfirmationThreshold(confirmationsThreshold)
            .setStorage(storage)
            .setApiTransactionProvider(apiProvider)
            .setApiSyncStateManager(apiState)
            .setBlockHeaderParser(headerParser)
            .setFixedDustThreshold(softDustLimit.toInt())
            .setUtxoSpendabilityPolicy(DogecoinCoinbaseMaturityPolicy())
            .setBroadcastFailurePolicy(BroadcastFailurePolicy.RetainPending)
            .setMinimumVerifiedBlockHeight(
                if (syncMode is SyncMode.Full) {
                    when (networkType) {
                        NetworkType.MainNet -> DogecoinConsensus.minimumVerifiedMainNetHeight
                        NetworkType.TestNet -> DogecoinConsensus.minimumVerifiedTestNetHeight
                    }
                } else 0
            )
            .setBlockValidator(blockValidatorSet(storage, networkType))

        // Recipient parsing, transaction extraction, and fee estimation all use
        // the builder's converter chain during construction.
        builder.addressConverter.prependConverter(base58Converter(network))
        if (syncMode !is SyncMode.Blockchair) {
            // Blockchair mode persists a synthetic API tip with bits=0 and is
            // explicitly an API trust mode. Full and API-assisted P2P sync have
            // validated targets and must select forks by accumulated proof.
            builder.setForkChoice(CumulativeWorkForkChoice(DogecoinConsensus.powLimit))
        }

        return builder.build().also { core ->
            // BitcoinCore's parser registry replaces parsers by command, so this
            // is also safe if a future builder adds the ordinary 80-byte parser.
            core.addMessageParser(DogecoinHeadersMessageParser(headerParser))
            core.addRestoreKeyConverter(Bip44RestoreKeyConverter(base58Converter(network)))
        }
    }

    companion object {
        const val recommendedFeeRate = 1_000 // koinu per serialized byte (0.01 DOGE/kB)
        const val softDustLimit = 1_000_000L // wallet relay policy (0.01 DOGE)
        const val hardDustLimit = 100_000L   // node hard rejection threshold (0.001 DOGE)

        val defaultNetworkType = NetworkType.MainNet
        val defaultSyncMode: SyncMode = SyncMode.Full()
        val defaultFullSyncStart = FullSyncStart.Bip44
        const val defaultPeerSize = 10
        const val defaultConfirmationsThreshold = 10

        fun firstAddress(
            seed: ByteArray,
            networkType: NetworkType = defaultNetworkType,
        ): Address = BitcoinCore.firstAddress(
            seed,
            Purpose.BIP44,
            network(networkType),
            addressConverter(network(networkType)),
        )

        fun firstAddress(
            extendedKey: HDExtendedKey,
            networkType: NetworkType = defaultNetworkType,
        ): Address = BitcoinCore.firstAddress(
            extendedKey,
            Purpose.BIP44,
            network(networkType),
            addressConverter(network(networkType)),
        )

        fun validateAddress(
            address: String,
            networkType: NetworkType = defaultNetworkType,
        ): Boolean = try {
            parseAddress(address, network(networkType))
            true
        } catch (_: Exception) {
            false
        }

        fun validateWatchAddress(
            address: String,
            networkType: NetworkType = defaultNetworkType,
        ): Boolean = try {
            parseAddress(address, network(networkType)).scriptType == ScriptType.P2PKH
        } catch (_: Exception) {
            false
        }

        fun clear(context: Context, networkType: NetworkType, walletId: String) {
            FullSyncStart.values().forEach { fullSyncStart ->
                listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair()).forEach { syncMode ->
                    try {
                        SQLiteDatabase.deleteDatabase(
                            context.getDatabasePath(
                                databaseName(networkType, walletId, syncMode, fullSyncStart)
                            )
                        )
                    } catch (_: Exception) {
                        // Continue clearing the remaining mode databases.
                    }
                }
            }
        }

        private fun blockValidatorSet(storage: Storage, networkType: NetworkType): BlockValidatorSet =
            BlockValidatorSet().apply {
                addBlockValidator(DogecoinProofOfWorkValidator())
                addBlockValidator(
                    DogecoinCheckpointValidator(
                        if (networkType == NetworkType.MainNet) {
                            DogecoinConsensus.mainNetCheckpoints
                        } else {
                            DogecoinConsensus.testNetCheckpoints
                        }
                    )
                )
                addBlockValidator(
                    DogecoinActivationValidator(
                        if (networkType == NetworkType.MainNet) {
                            DogecoinConsensus.mainNetAuxPowHeight
                        } else {
                            DogecoinConsensus.testNetAuxPowHeight
                        }
                    )
                )
                addBlockValidator(
                    DogecoinDifficultyValidator(
                        BlockValidatorHelper(storage),
                        testNet = networkType == NetworkType.TestNet,
                    )
                )
                addBlockValidator(
                    DogecoinContextualValidator(
                        BlockValidatorHelper(storage),
                        if (networkType == NetworkType.MainNet) {
                            DogecoinVersionActivations.MainNet
                        } else {
                            DogecoinVersionActivations.TestNet
                        },
                    )
                )
            }

        internal fun resolveCheckpoint(
            syncMode: SyncMode,
            network: Network,
            storage: Storage,
            fullSyncStart: FullSyncStart,
        ): Checkpoint {
            if (syncMode !is SyncMode.Full) {
                return Checkpoint.resolveCheckpoint(syncMode, network, storage)
            }

            val checkpoint = when (fullSyncStart) {
                FullSyncStart.Bip44 -> network.bip44Checkpoint
                FullSyncStart.RecentPinned -> network.lastCheckpoint
            }
            val lastBlock = storage.lastBlock()
            val activeLastBlock = storage.getBlock(stale = false, sortedHeight = "DESC")
            if (
                lastBlock != null &&
                (activeLastBlock == null || activeLastBlock.height < checkpoint.block.height)
            ) {
                // A database created by an older release cannot safely use a
                // newly shipped checkpoint it has never seen. Fall back to the
                // early canonical anchor; BlockSyncer will build locators from
                // the existing, later stored tip.
                return network.bip44Checkpoint
            }
            if (lastBlock == null) {
                storage.saveBlock(checkpoint.block)
                checkpoint.additionalBlocks.forEach(storage::saveBlock)
            }
            return checkpoint
        }

        private fun network(networkType: NetworkType): Network = when (networkType) {
            NetworkType.MainNet -> MainNetDogecoin()
            NetworkType.TestNet -> TestNetDogecoin()
        }

        private fun base58Converter(network: Network) =
            Base58AddressConverter(network.addressVersion, network.addressScriptVersion)

        private fun addressConverter(network: Network) = AddressConverterChain().apply {
            prependConverter(base58Converter(network))
        }

        private fun parseAddress(address: String, network: Network): Address =
            addressConverter(network).convert(address)

        internal fun databaseNames(networkType: NetworkType, walletId: String): List<String> =
            FullSyncStart.values().flatMap { fullSyncStart ->
                listOf(SyncMode.Api(), SyncMode.Full(), SyncMode.Blockchair()).map { syncMode ->
                    databaseName(networkType, walletId, syncMode, fullSyncStart)
                }
            }

        internal fun databaseName(
            networkType: NetworkType,
            walletId: String,
            syncMode: SyncMode,
            fullSyncStart: FullSyncStart,
        ) = "Dogecoin-${networkType.name}-$walletId-${syncMode.javaClass.simpleName}-BIP44-${fullSyncStart.name}"
    }
}
