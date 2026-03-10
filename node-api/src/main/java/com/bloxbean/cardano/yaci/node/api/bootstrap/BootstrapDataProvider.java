package com.bloxbean.cardano.yaci.node.api.bootstrap;

import java.util.List;

/**
 * Interface for fetching blockchain data during bootstrap.
 * Implementations provide block metadata and UTXO data from external sources
 * such as Blockfrost or Koios APIs.
 */
public interface BootstrapDataProvider {

    /**
     * Fetch the latest block on the chain.
     *
     * @return latest block metadata
     */
    BootstrapBlockInfo getLatestBlock();

    /**
     * Fetch block metadata for a range of block numbers (inclusive).
     *
     * @param fromBlockNumber start block number (inclusive)
     * @param toBlockNumber   end block number (inclusive)
     * @return list of block metadata ordered by block number ascending
     */
    List<BootstrapBlockInfo> getBlocks(long fromBlockNumber, long toBlockNumber);

    /**
     * Fetch all current UTXOs at a payment address.
     *
     * @param address bech32 payment address (addr1... or addr_test1...)
     * @return list of UTXOs
     */
    List<BootstrapUtxo> getUtxosByAddress(String address);

    /**
     * Fetch all current UTXOs at addresses controlled by a stake address.
     *
     * @param stakeAddress bech32 stake address (stake1... or stake_test1...)
     * @return list of UTXOs
     */
    List<BootstrapUtxo> getUtxosByStakeAddress(String stakeAddress);

    /**
     * Fetch a specific UTXO by transaction hash and output index.
     *
     * @param txHash      transaction hash (hex)
     * @param outputIndex output index
     * @return the UTXO, or null if not found (may have been spent)
     */
    BootstrapUtxo getUtxo(String txHash, int outputIndex);
}
