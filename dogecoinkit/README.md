# DogecoinKit

DogecoinKit is a legacy-P2PKH SPV wallet module built on this repository's
BitcoinCore architecture. It is an independent implementation and is not
endorsed by the Dogecoin Foundation or Dogecoin Core maintainers.

## Consensus source and attribution

The consensus source of truth is the stable Dogecoin Core `v1.14.9` release at
commit `e0a1c157791544e818c901bd9341896965afbf9d`. The Kotlin logic translating
`src/auxpow.cpp`, `src/dogecoin.cpp`, `src/pow.cpp`, `src/validation.cpp`, and
`src/chainparams.cpp` remains covered by the upstream MIT license and this
repository's MIT attribution.

The module validates the double-SHA256 identifier of the pure 80-byte child
header, scrypt-1024-1-1 proof of work, variable-length AuxPoW, merged-mining
commitments, chain IDs, activation boundaries, exact legacy/DigiShield work,
median-time-past, the two-hour future-time ceiling, Core base-version
activations, canonical CompactSize encodings, fixed Core checkpoints, and
connected-branch cumulative-work fork choice. Candidate 145,000 already uses
the height-145,000 DigiShield parameters, one-block interval, and DigiShield
calculation; the previous-height `fNewDifficultyProtocol` switch flips for
candidate 145,001. Mainnet AuxPoW begins at 371,337. Testnet's corresponding
policy changes are 145,000, 157,500, and 158,100.

The future-time ceiling currently uses the Android device clock rather than
Dogecoin Core's bounded, peer-adjusted network time. Peer timestamps cannot
loosen that ceiling, but a device clock more than roughly two hours behind can
stall otherwise valid P2P synchronization.

## Keys and addresses

Mainnet derives compressed legacy P2PKH addresses at BIP44 coin type 3
(`m/44'/3'/account'/change/index`). Testnet uses the conventional test coin type
1. Seed and account-key wallets derive P2PKH only; watch wallets accept P2PKH
only. P2SH addresses are valid payment destinations, but this kit does not
derive P2SH, SegWit, or Taproot receive addresses.

## Checkpoints and full-sync start policy

`FullSyncStart.Bip44` is the conservative constructor default for restored and
watch wallets. Its mainnet anchor is canonical block 145,000,
`cc47cae70d7c5c92828d3214a266331dde59087d4a39071fa76ddfff9b7bde72`
(2014-03-17), before BIP44 wallet use. `FullSyncStart.RecentPinned` is intended
only for newly created accounts and anchors at Dogecoin Core v1.14.9's
height-5,050,000 `defaultAssumeValid` block,
`e7d4577405223918491477db725a393bcfc349d8ee63b0a4fde23cbfbfd81dea`.
Each mainnet resource includes the preceding ten contiguous headers so the
first downloaded successor can perform both DigiShield and 11-block
median-time validation. The start policy is part of the database name to avoid
reusing incompatible state. If a newer release rotates the recent checkpoint,
an older partially synced database falls back to the early anchor and builds
its locator from the highest active stored tip, never from an unseen anchor.

Full P2P traversal enforces every Dogecoin Core v1.14.9 checkpoint from the
mainnet BIP44 anchor through height 5,050,000 and every testnet checkpoint from
483,173 through 5,900,000. Peers below the respective last checkpoint cannot
be accepted or reported as verified-synced. These height floors preserve the
checkpoint/minimum-work trust assumption; they do not replace per-header proof
and contextual validation.

The checkpoint headers were retrieved from Blockchair's raw Dogecoin block API,
re-hashed locally from their serialized 80-byte headers, hash-linked, and
cross-checked at both anchors against BlockCypher. The real AuxPoW fixture in
`AuxPowBlockHeaderParserTest` is the complete serialized header for block
5,050,000; its expected parent scrypt hash is
`00000000000000d6574469fdfcf3f6f451a69302b46fe6db5049ac9c44820761`.
Testnet currently starts traversal at genesis for both policies, so a practical
testnet sync to tip has not been established, even though its fixed checkpoints
and 5,900,000 verified-height floor are enforced.

## Sync, broadcast, and trust boundaries

The default is P2P full SPV sync and P2P broadcast. API-assisted sync uses the
Blockchair-compatible service for address-history discovery. Its discovered
merkle blocks are force-added by the shared core and therefore skip chained
context checks such as expected difficulty, activation height, median time,
future time, base version, and fixed checkpoint matching. The custom parser
still validates structural AuxPoW and scrypt work against each header's claimed
target. `SyncMode.Blockchair` is a stronger API trust mode: it stores a
synthetic API tip and therefore does not receive cumulative-work fork
guarantees. In either API-dependent mode the service can omit or fabricate
wallet history and confirmations (with a cooperating peer where P2P data is
involved), but it cannot derive keys or forge wallet signatures. Neither mode
should be presented as trustless.

Dogecoin broadcast ambiguity is handled conservatively. A timeout, HTTP failure, or P2P
non-response never marks a transaction invalid or unlocks its inputs. The same
raw transaction is retried a bounded number of times and remains pending until
chain/mempool synchronization supplies an authoritative outcome. DogecoinKit
does not opt into API broadcast by default. Other Bitcoin-family kits retain
their historical invalidation behavior unless they explicitly select this
policy.

## Fee and dust policy

- Recommended fee rate: 1,000 koinu per serialized byte (0.01 DOGE/kB).
- Wallet soft dust: outputs below 1,000,000 koinu (0.01 DOGE) are not created.
- Node hard dust: 100,000 koinu (0.001 DOGE), exposed separately for policy UI.
- An output exactly equal to 1,000,000 koinu is not dust, matching Core's `<`
  comparison.

Avoiding outputs between the hard and soft thresholds also avoids silently
depending on Core's additional 0.01 DOGE soft-dust fee.

Coinbase outputs are persisted as transaction-level metadata and remain locked
for Core's height-dependent maturity: 30 blocks before height 145,000 and 240
blocks from height 145,000. Normal payments keep their ordinary confirmation
policy. This avoids relying on the shared input table's historical primary key,
under which multiple coinbase null prevouts can collide.

## Verification and remaining work

Focused unit coverage includes Core difficulty vectors, activation and testnet
minimum-difficulty boundaries, real and synthetic AuxPoW, multi-header parsing,
checkpoint provenance/history, contextual checks, cumulative-work reorgs,
connected sibling-fork isolation, fixed dust and exact equality, height-aware
coinbase maturity with a real Room collision/migration regression, Blockchair
pagination, ambiguous API/P2P broadcasts, BIP44 derivation, and an independently
checked deterministic P2PKH signature/raw transaction. The THOR-style signing
gate pays `D9EGy2TqzyLmL1PJ9K8LhoM9HuZaogDkJY`, includes memo
`=:THOR.RUNE:thor1destination`, uses the route-style 750,000-koinu/byte fee
rate, and pins transaction id
`f6d6c05a8e0311de5b4bbb5d37c1b00fbb8f2afccf4a138a30939e88726f126a`.

Run with JDK 17 and an Android SDK:

```sh
./gradlew :dogecoinkit:testDebugUnitTest
./gradlew :bitcoincore:assembleDebug :dogecoinkit:assembleDebug
```

This module is not yet production-certified. A live mainnet sync to current tip,
a practical testnet checkpoint/sync, adversarial live reorg drills, Android
instrumentation, API failure testing against the deployed endpoint, and an
independent security review remain required. The pinned upstream repository's
older `bitcoincore` test sources also need reconciliation with their current
main APIs before its entire historical unit-test module can compile; the new
shared-core regressions run from DogecoinKit in the meantime.
