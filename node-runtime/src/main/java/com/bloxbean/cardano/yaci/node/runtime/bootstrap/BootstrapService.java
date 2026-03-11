package com.bloxbean.cardano.yaci.node.runtime.bootstrap;

import com.bloxbean.cardano.yaci.core.storage.ChainTip;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.bootstrap.*;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.DevnetBlockBuilder;
import com.bloxbean.cardano.yaci.node.runtime.chain.DirectRocksDBChainState;
import com.bloxbean.cardano.yaci.node.runtime.utxo.UtxoStoreWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates the bootstrap process: creates synthetic chain state entries and injects UTXOs
 * from an external data provider, enabling a node to start syncing from near-tip without
 * full chain sync from genesis.
 */
public class BootstrapService {
    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    /** Number of consecutive blocks to seed in the chain state */
    private static final int BOOTSTRAP_BLOCK_COUNT = 10;

    /** Safety margin when using "latest" — subtract this from current tip */
    private static final int LATEST_SAFETY_MARGIN = 100;

    private final DirectRocksDBChainState chainState;
    private final UtxoStoreWriter utxoStore;

    public BootstrapService(DirectRocksDBChainState chainState, UtxoStoreWriter utxoStore) {
        this.chainState = chainState;
        this.utxoStore = utxoStore;
    }

    /**
     * Perform a full bootstrap: create synthetic chain state and inject UTXOs.
     *
     * @param blockNumber block number to bootstrap from (use &lt;= 0 for "latest")
     * @param addresses   list of payment addresses (addr1..) and/or stake addresses (stake1..)
     * @param outpoints   specific UTXOs to fetch by tx hash + output index (may be empty)
     * @param provider    data provider (Blockfrost, Koios, etc.)
     * @return bootstrap result with stats
     */
    public BootstrapResult bootstrap(long blockNumber, List<String> addresses,
                                     List<BootstrapOutpoint> outpoints,
                                     BootstrapDataProvider provider) {
        // 1. Validate preconditions
        ChainTip existingTip = chainState.getTip();
        ChainTip existingHeaderTip = chainState.getHeaderTip();
        if (existingTip != null || existingHeaderTip != null) {
            throw new IllegalStateException(
                    "Chain state already exists (tip=" + existingTip + ", headerTip=" + existingHeaderTip
                            + "). Bootstrap is only allowed on empty chain state. "
                            + "Delete the RocksDB directory and restart to bootstrap.");
        }

        // 2. Resolve "latest" block number
        if (blockNumber <= 0) {
            log.info("Bootstrap: resolving 'latest' block number...");
            BootstrapBlockInfo latest = provider.getLatestBlock();
            blockNumber = latest.blockNumber() - LATEST_SAFETY_MARGIN;
            log.info("Bootstrap: using block #{} (latest={}, margin={})",
                    blockNumber, latest.blockNumber(), LATEST_SAFETY_MARGIN);
        }

        // 3. Fetch block metadata for [blockNumber - (N-1) .. blockNumber]
        long fromBlock = blockNumber - (BOOTSTRAP_BLOCK_COUNT - 1);
        log.info("Bootstrap: fetching block metadata for range [{} .. {}]", fromBlock, blockNumber);
        List<BootstrapBlockInfo> blocks = provider.getBlocks(fromBlock, blockNumber);
        if (blocks.isEmpty()) {
            throw new RuntimeException("Bootstrap: provider returned no blocks for range ["
                    + fromBlock + " .. " + blockNumber + "]");
        }

        // 4. Store synthetic chain entries
        log.info("Bootstrap: storing {} synthetic chain entries...", blocks.size());
        storeSyntheticChain(blocks);

        // 5. Fetch and inject UTXOs
        BootstrapBlockInfo tipBlock = blocks.get(blocks.size() - 1);
        int totalUtxos = injectUtxos(addresses, outpoints, tipBlock, provider);

        BootstrapResult result = new BootstrapResult(
                tipBlock.blockNumber(), tipBlock.blockHash(), tipBlock.slot(),
                blocks.size(), totalUtxos);

        log.info("***********************************************************************");
        log.info("*** Bootstrap complete: block #{}, slot={}, hash={}, utxos={}",
                result.blockNumber(), result.slot(), result.blockHash(), result.utxosInjected());
        log.info("***********************************************************************");

        return result;
    }

    /**
     * Refresh/add UTXOs for new addresses on an already-running node.
     * Does NOT recreate the chain state — injects UTXOs at the current tip.
     *
     * @param addresses list of addresses to fetch UTXOs for
     * @param outpoints specific UTXOs to fetch
     * @param provider  data provider
     * @return number of UTXOs injected
     */
    public int refreshUtxos(List<String> addresses, List<BootstrapOutpoint> outpoints,
                            BootstrapDataProvider provider) {
        ChainTip tip = chainState.getTip();
        if (tip == null) {
            throw new IllegalStateException(
                    "No chain state exists. Run a full bootstrap first.");
        }

        BootstrapBlockInfo tipInfo = new BootstrapBlockInfo(
                HexUtil.encodeHexString(tip.getBlockHash()),
                tip.getBlockNumber(), tip.getSlot(), null);

        int count = injectUtxos(addresses, outpoints, tipInfo, provider);
        log.info("UTXO refresh complete: {} UTXOs injected at block #{}", count, tip.getBlockNumber());
        return count;
    }

    private void storeSyntheticChain(List<BootstrapBlockInfo> blocks) {
        DevnetBlockBuilder blockBuilder = new DevnetBlockBuilder();

        for (int i = 0; i < blocks.size(); i++) {
            BootstrapBlockInfo block = blocks.get(i);

            // Build synthetic block with dummy content
            byte[] prevHash = block.previousBlockHash() != null
                    ? HexUtil.decodeHexString(block.previousBlockHash()) : null;
            DevnetBlockBuilder.BlockBuildResult built = blockBuilder.buildBlock(
                    block.blockNumber(), block.slot(), prevHash, Collections.emptyList());

            // Store with the REAL hash from the provider (not the computed hash from dummy header)
            byte[] realHash = HexUtil.decodeHexString(block.blockHash());

            chainState.forceStoreBlockHeader(realHash, block.blockNumber(),
                    block.slot(), built.wrappedHeaderCbor());
            chainState.forceStoreBlock(realHash, block.blockNumber(),
                    block.slot(), built.blockCbor());
        }
    }

    private int injectUtxos(List<String> addresses, List<BootstrapOutpoint> outpoints,
                            BootstrapBlockInfo tipBlock, BootstrapDataProvider provider) {
        List<BootstrapUtxo> allUtxos = new ArrayList<>();

        // Fetch UTXOs by address (auto-detect address type)
        if (addresses != null) {
            for (String addr : addresses) {
                try {
                    List<BootstrapUtxo> utxos = resolveUtxosByAddress(addr, provider);
                    allUtxos.addAll(utxos);
                    log.info("Bootstrap: fetched {} UTXOs for {}", utxos.size(), truncateAddress(addr));
                } catch (Exception e) {
                    log.warn("Bootstrap: failed to fetch UTXOs for {} - skipping ({})",
                            truncateAddress(addr), e.getMessage());
                }
            }
        }

        // Fetch specific UTXOs by outpoint
        if (outpoints != null) {
            for (BootstrapOutpoint outpoint : outpoints) {
                BootstrapUtxo utxo = provider.getUtxo(outpoint.txHash(), outpoint.outputIndex());
                if (utxo != null) {
                    allUtxos.add(utxo);
                    log.info("Bootstrap: fetched UTXO {}#{}", truncateHash(outpoint.txHash()), outpoint.outputIndex());
                } else {
                    log.warn("Bootstrap: UTXO not found (may be spent): {}#{}",
                            outpoint.txHash(), outpoint.outputIndex());
                }
            }
        }

        // Inject all UTXOs
        if (!allUtxos.isEmpty()) {
            utxoStore.injectBootstrapUtxos(allUtxos, tipBlock.blockNumber(),
                    tipBlock.slot(), tipBlock.blockHash());
        }

        return allUtxos.size();
    }

    private List<BootstrapUtxo> resolveUtxosByAddress(String address, BootstrapDataProvider provider) {
        if (address.startsWith("stake1") || address.startsWith("stake_test1")) {
            return provider.getUtxosByStakeAddress(address);
        }
        return provider.getUtxosByAddress(address);
    }

    private static String truncateAddress(String addr) {
        if (addr.length() > 20) {
            return addr.substring(0, 12) + "..." + addr.substring(addr.length() - 6);
        }
        return addr;
    }

    private static String truncateHash(String hash) {
        if (hash.length() > 16) {
            return hash.substring(0, 8) + "..." + hash.substring(hash.length() - 4);
        }
        return hash;
    }
}
