package io.horizontalsystems.dogecoinkit

import io.horizontalsystems.bitcoincore.crypto.CompactBits
import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import java.math.BigInteger

/** Consensus constants pinned to Dogecoin Core v1.14.9. */
object DogecoinConsensus {
    const val chainId = 0x62
    const val auxPowVersionFlag = 1 shl 8
    const val digiShieldHeight = 145_000
    const val mainNetAuxPowHeight = 371_337
    const val testNetMinDifficultyHeight = 157_500
    const val testNetAuxPowHeight = 158_100
    const val targetSpacing = 60L
    const val legacyTargetTimespan = 14_400L
    const val legacyDifficultyInterval = 240
    const val maxTargetBits = 0x1e0fffffL
    const val minimumVerifiedMainNetHeight = 5_050_000
    const val minimumVerifiedTestNetHeight = 5_900_000

    val powLimit: BigInteger = CompactBits.decode(maxTargetBits)

    /** Exact mainnet checkpoints from v1.14.9 src/chainparams.cpp. */
    val mainNetCheckpoints: Map<Int, ByteArray> = mapOf(
        145_000 to "cc47cae70d7c5c92828d3214a266331dde59087d4a39071fa76ddfff9b7bde72",
        371_337 to "60323982f9c5ff1b5a954eac9dc1269352835f47c2c5222691d80f0d50dcf053",
        450_000 to "d279277f8f846a224d776450aa04da3cf978991a182c6f3075db4c48b173bbd7",
        771_275 to "1b7d789ed82cbdc640952e7e7a54966c6488a32eaad54fc39dff83f310dbaaed",
        1_000_000 to "6aae55bea74235f0c80bd066349d4440c31f2d0f27d54265ecd484d8c1d11b47",
        1_250_000 to "00c7a442055c1a990e11eea5371ca5c1c02a0677b33cc88ec728c45edc4ec060",
        1_500_000 to "f1d32d6920de7b617d51e74bdf4e58adccaa582ffdc8657464454f16a952fca6",
        1_750_000 to "5c8e7327984f0d6f59447d89d143e5f6eafc524c82ad95d176c5cec082ae2001",
        2_000_000 to "9914f0e82e39bbf21950792e8816620d71b9965bdbbc14e72a95e3ab9618fea8",
        2_031_142 to "893297d89afb7599a3c571ca31a3b80e8353f4cf39872400ad0f57d26c4c5d42",
        2_250_000 to "0a87a8d4e40dca52763f93812a288741806380cd569537039ee927045c6bc338",
        2_510_150 to "77e3f4a4bcb4a2c15e8015525e3d15b466f6c022f6ca82698f329edef7d9777e",
        2_750_000 to "d4f8abb835930d3c4f92ca718aaa09bef545076bd872354e0b2b85deefacf2e3",
        3_000_000 to "195a83b091fb3ee7ecb56f2e63d01709293f57f971ccf373d93890c8dc1033db",
        3_250_000 to "7f3e28bf9e309c4b57a4b70aa64d3b2ea5250ae797af84976ddc420d49684034",
        3_500_000 to "eaa303b93c1c64d2b3a2cdcf6ccf21b10cc36626965cc2619661e8e1879abdfb",
        3_606_083 to "954c7c66dee51f0a3fb1edb26200b735f5275fe54d9505c76ebd2bcabac36f1e",
        3_854_173 to "e4b4ecda4c022406c502a247c0525480268ce7abbbef632796e8ca1646425e75",
        3_963_597 to "2b6927cfaa5e82353d45f02be8aadd3bfd165ece5ce24b9bfa4db20432befb5d",
        4_303_965 to "ed7d266dcbd8bb8af80f9ccb8deb3e18f9cc3f6972912680feeb37b090f8cee0",
        5_050_000 to "e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea",
    ).mapValues { (_, displayHash) -> displayHash.hexToByteArray().reversedArray() }

    /** Exact testnet checkpoints from v1.14.9 src/chainparams.cpp. */
    val testNetCheckpoints: Map<Int, ByteArray> = mapOf(
        483_173 to "a804201ca0aceb7e937ef7a3c613a9b7589245b10cc095148c4ce4965b0b73b5",
        591_117 to "5f6b93b2c28cedf32467d900369b8be6700f0649388a7dbfd3ebd4a01b1ffad8",
        658_924 to "ed6c8324d9a77195ee080f225a0fca6346495e08ded99bcda47a8eea5a8a620b",
        703_635 to "839fa54617adcd582d53030a37455c14a87a806f6615aa8213f13e196230ff7f",
        1_000_000 to "1fe4d44ea4d1edb031f52f0d7c635db8190dc871a190654c41d2450086b8ef0e",
        1_202_214 to "a2179767a87ee4e95944703976fee63578ec04fa3ac2fc1c9c2c83587d096977",
        1_250_000 to "b46affb421872ca8efa30366b09694e2f9bf077f7258213be14adb05a9f41883",
        1_500_000 to "0caa041b47b4d18a4f44bdc05cef1a96d5196ce7b2e32ad3e4eb9ba505144917",
        1_750_000 to "8042462366d854ad39b8b95ed2ca12e89a526ceee5a90042d55ebb24d5aab7e9",
        2_000_000 to "d6acde73e1b42fc17f29dcc76f63946d378ae1bd4eafab44d801a25be784103c",
        2_250_000 to "c4342ae6d9a522a02e5607411df1b00e9329563ef844a758d762d601d42c86dc",
        2_500_000 to "3a66ec4933fbb348c9b1889aaf2f732fe429fd9a8f74fee6895eae061ac897e2",
        2_750_000 to "473ea9f625d59f534ffcc9738ffc58f7b7b1e0e993078614f5484a9505885563",
        3_062_910 to "113c41c00934f940a41f99d18b2ad9aefd183a4b7fe80527e1e6c12779bd0246",
        3_286_675 to "07fef07a255d510297c9189dc96da5f4e41a8184bc979df8294487f07fee1cf3",
        3_445_426 to "70574db7856bd685abe7b0a8a3e79b29882620645bd763b01459176bceb58cd1",
        3_976_284 to "af23c3e750bb4f2ce091235f006e7e4e2af453d4c866282e7870471dcfeb4382",
        5_900_000 to "199bea6a442310589cbb50a193a30b097c228bd5a0f21af21e4e53dd57c382d3",
    ).mapValues { (_, displayHash) -> displayHash.hexToByteArray().reversedArray() }

    fun isAuxPow(version: Int): Boolean = version and auxPowVersionFlag != 0

    fun chainId(version: Int): Int = version shr 16

    /** CPureBlockHeader::GetBaseVersion uses signed remainder, not a bit mask. */
    fun baseVersion(version: Int): Int = version % auxPowVersionFlag

    fun isLegacy(version: Int): Boolean =
        version == 1 || (version == 2 && chainId(version) == 0)
}
