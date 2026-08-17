package io.horizontalsystems.bitcoincore.blocks

import io.horizontalsystems.bitcoincore.blocks.validators.BlockValidatorException
import io.horizontalsystems.bitcoincore.blocks.validators.IBlockValidator
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.models.Block
import io.horizontalsystems.bitcoincore.models.MerkleBlock
import io.horizontalsystems.bitcoincore.storage.BlockHeader

class Blockchain(
    private val storage: IStorage,
    private val blockValidator: IBlockValidator?,
    private val dataListener: IBlockchainDataListener,
    private val forkChoice: ForkChoice = LongestHeightForkChoice(),
) {

    fun connect(merkleBlock: MerkleBlock): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            return blockInDB
        }

        val parentBlock = storage.getBlock(merkleBlock.header.previousBlockHeaderHash) ?: throw BlockValidatorException.NoPreviousBlock()

        val block = Block(merkleBlock.header, parentBlock)
        blockValidator?.validate(block, parentBlock)

        block.stale = true

        if (block.height % 2016 == 0) {
            storage.deleteBlocksWithoutTransactions(block.height - 2016)
        }

        return addBlockAndNotify(block)
    }

    fun forceAdd(merkleBlock: MerkleBlock, height: Int): Block {
        val blockInDB = storage.getBlock(merkleBlock.blockHash)
        if (blockInDB != null) {
            return blockInDB
        }

        return addBlockAndNotify(Block(merkleBlock.header, height))
    }

    fun insertLastBlock(header: BlockHeader, height: Int) {
        if (storage.getBlock(header.hash) != null) {
            return
        }

        addBlockAndNotify(Block(header, height))
    }

    fun handleFork() {
        val firstStaleHeight = storage.getBlock(stale = true, sortedHeight = "ASC")?.height ?: return

        val activeBranch = storage.getBlocks(
            heightGreaterOrEqualTo = firstStaleHeight,
            stale = false,
        ).sortedBy(Block::height)
        val staleBlocks = storage.getBlocks(stale = true)
        val candidatePaths = connectedCandidatePaths(staleBlocks, activeBranch.isEmpty())

        var bestEvaluationBranch = activeBranch
        var winningPath: List<Block>? = null

        candidatePaths.forEach { path ->
            // Candidates may split from active at different heights. Include
            // the still-common active prefix so all comparisons cover the same
            // post-fork interval; its work then cancels naturally.
            val evaluationBranch = activeBranch.filter { it.height < path.first().height } + path
            if (forkChoice.shouldSwitch(bestEvaluationBranch, evaluationBranch)) {
                bestEvaluationBranch = evaluationBranch
                winningPath = path
            }
        }

        val winner = winningPath
        if (winner == null) {
            deleteBlocks(staleBlocks)
            return
        }

        deleteBlocks(activeBranch.filter { it.height >= winner.first().height })

        val winningHashes = winner.map { it.headerHash.toHexString() }.toSet()
        val losingStaleBlocks = staleBlocks.filterNot { it.headerHash.toHexString() in winningHashes }
        if (losingStaleBlocks.isNotEmpty()) {
            deleteBlocks(losingStaleBlocks)
            winner.forEach { block ->
                block.stale = false
                storage.updateBlock(block)
            }
        } else {
            // Preserve the historical fast path for the common single-branch
            // case while never activating unrelated sibling branches.
            storage.unstaleAllBlocks()
        }
    }

    private fun connectedCandidatePaths(
        staleBlocks: List<Block>,
        allowMissingRootParent: Boolean,
    ): List<List<Block>> {
        if (staleBlocks.isEmpty()) return emptyList()

        val byHash = staleBlocks.associateBy { it.headerHash.toHexString() }
        val staleParentHashes = staleBlocks.map { it.previousBlockHash.toHexString() }.toSet()
        val leaves = staleBlocks
            .filter { it.headerHash.toHexString() !in staleParentHashes }
            .sortedWith(compareByDescending<Block> { it.height }.thenBy { it.headerHash.toHexString() })

        return leaves.mapNotNull { leaf ->
            val reversed = mutableListOf<Block>()
            val visited = mutableSetOf<String>()
            var cursor: Block? = leaf

            while (cursor != null) {
                val hash = cursor.headerHash.toHexString()
                if (!visited.add(hash)) return@mapNotNull null
                reversed.add(cursor)
                val parent = byHash[cursor.previousBlockHash.toHexString()]
                if (parent != null && parent.height + 1 != cursor.height) return@mapNotNull null
                cursor = parent
            }

            val path = reversed.asReversed()
            val root = path.first()
            val rootParent = storage.getBlock(root.previousBlockHash)
            if (
                rootParent == null && !allowMissingRootParent ||
                rootParent != null && (rootParent.stale || rootParent.height + 1 != root.height)
            ) {
                null
            } else {
                path
            }
        }
    }

    fun deleteBlocks(blocksToDelete: List<Block>) {
        val deletedTransactionIds = mutableListOf<String>()

        blocksToDelete.forEach { block ->
            deletedTransactionIds.addAll(storage.getBlockTransactions(block).map { it.hash.toReversedHex() })
        }

        storage.deleteBlocks(blocksToDelete)

        dataListener.onTransactionsDelete(deletedTransactionIds)
    }

    private fun addBlockAndNotify(block: Block): Block {
        storage.addBlock(block)
        dataListener.onBlockInsert(block)

        return block
    }
}
