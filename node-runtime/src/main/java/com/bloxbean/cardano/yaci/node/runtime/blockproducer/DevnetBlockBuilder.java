package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Builds structurally valid Conway-era CBOR blocks for devnet block production.
 * No real cryptography — uses dummy zero-filled byte arrays of correct lengths.
 * <p>
 * Produces two outputs per block:
 * - Full block CBOR for ChainState.storeBlock(): [6, [header, tx_bodies, witnesses, aux_data, invalid_txs]]
 * - Wrapped header CBOR for ChainState.storeBlockHeader(): [6, 24(h'serialized_header_array')]
 */
@Slf4j
public class DevnetBlockBuilder {

    // HardFork combinator era index (cardano-node 10.6+): Byron=0, Shelley=1, ..., Babbage=5, Conway=6, Dijkstra=7
    // Used for both BlockFetch WrappedBlock and ChainSync WrappedHeader
    protected static final int CONWAY_ERA_ID = 6;
    private static final int HASH_LENGTH = 32;
    private static final int VKEY_LENGTH = 32;
    private static final int VRF_VKEY_LENGTH = 32;
    private static final int VRF_PROOF_LENGTH = 80;
    private static final int VRF_OUTPUT_LENGTH = 64;
    private static final int KES_SIGNATURE_LENGTH = 448;
    private static final int OPCERT_SIGMA_LENGTH = 64;

    // Conway protocol version
    protected static final long PROTOCOL_MAJOR = 10;
    protected static final long PROTOCOL_MINOR = 0;

    /**
     * Result of building a block: full block CBOR and wrapped header CBOR.
     */
    public record BlockBuildResult(
            byte[] blockCbor,
            byte[] wrappedHeaderCbor,
            byte[] blockHash,
            long blockNumber,
            long slot
    ) {
    }

    /**
     * Build a Conway-era block.
     *
     * @param blockNumber  the block number
     * @param slot         the slot number
     * @param prevHash     the previous block hash (null for genesis)
     * @param transactions list of complete transaction CBOR bytes (each is [body, witnesses, is_valid, aux_data])
     * @return BlockBuildResult with full block, wrapped header, and computed block hash
     */
    public BlockBuildResult buildBlock(long blockNumber, long slot, byte[] prevHash,
                                       List<byte[]> transactions) {
        // 1. Compute block body
        BlockBodyResult body = computeBlockBody(transactions);

        // 2. Build header array: [[header_body], signature]
        Array headerArray = buildHeaderArray(blockNumber, slot, prevHash, body.bodySize(), body.bodyHash());

        // 3-5. Compute block hash, assemble full block and wrapped header
        return assembleBlock(headerArray, body, blockNumber, slot, transactions != null ? transactions.size() : 0);
    }

    /**
     * Compute block hash, assemble full block CBOR and wrapped header CBOR from a header array and body.
     *
     * @param headerArray  the CBOR header array [headerBody, signature]
     * @param body         the block body result
     * @param blockNumber  the block number (for logging and result)
     * @param slot         the slot number (for logging and result)
     * @param txCount      transaction count (for logging)
     * @return BlockBuildResult with full block, wrapped header, and computed block hash
     */
    protected BlockBuildResult assembleBlock(Array headerArray, BlockBodyResult body,
                                              long blockNumber, long slot, int txCount) {
        // Compute block hash = blake2b-256(serialized header array)
        byte[] headerArrayBytes = CborSerializationUtil.serialize(headerArray);
        byte[] blockHash = Blake2bUtil.blake2bHash256(headerArrayBytes);

        // Build full block: [eraId, [header, tx_bodies, witnesses, aux_data, invalid_txs]]
        Array blockContentArray = new Array();
        blockContentArray.add(headerArray);
        blockContentArray.add(body.txBodiesArray());
        blockContentArray.add(body.txWitnessesArray());
        blockContentArray.add(body.auxDataMap());
        blockContentArray.add(body.invalidTxsArray());

        Array fullBlock = new Array();
        fullBlock.add(new UnsignedInteger(CONWAY_ERA_ID));
        fullBlock.add(blockContentArray);
        byte[] blockCbor = CborSerializationUtil.serialize(fullBlock);

        // Build wrapped header (ChainSync format):
        //    [eraId, 24(h'<serialized_header_array>')]
        //    eraId=6 for Conway (different from blockType=7 used in BlockFetch)
        Array wrappedHeader = new Array();
        wrappedHeader.add(new UnsignedInteger(CONWAY_ERA_ID));
        ByteString headerByteString = new ByteString(headerArrayBytes);
        headerByteString.setTag(24L);
        wrappedHeader.add(headerByteString);
        byte[] wrappedHeaderCbor = CborSerializationUtil.serialize(wrappedHeader);

        log.debug("Built block #{} at slot {} with {} txs, bodySize={}, blockHash={}",
                blockNumber, slot, txCount, body.bodySize(), HexUtil.encodeHexString(blockHash));

        return new BlockBuildResult(blockCbor, wrappedHeaderCbor, blockHash, blockNumber, slot);
    }

    /**
     * Result of computing the block body from transactions.
     */
    public record BlockBodyResult(
            Array txBodiesArray,
            Array txWitnessesArray,
            Map auxDataMap,
            Array invalidTxsArray,
            long bodySize,
            byte[] bodyHash
    ) {
    }

    /**
     * Compute the block body arrays and body hash from a list of transactions.
     *
     * @param transactions list of complete transaction CBOR bytes
     * @return BlockBodyResult with parallel arrays and computed body hash
     */
    protected BlockBodyResult computeBlockBody(List<byte[]> transactions) {
        Array txBodiesArray = new Array();
        Array txWitnessesArray = new Array();
        Map auxDataMap = new Map();
        Array invalidTxsArray = new Array();

        if (transactions != null) {
            for (int i = 0; i < transactions.size(); i++) {
                splitTransaction(transactions.get(i), i, txBodiesArray, txWitnessesArray, auxDataMap);
            }
        }

        // Alonzo/Conway segregated witness body hash (two-level):
        // 1. Hash each component individually
        // 2. Concatenate the four 32-byte hashes
        // 3. Hash the 128-byte concatenation
        byte[] txBodiesBytes = CborSerializationUtil.serialize(txBodiesArray);
        byte[] txWitnessesBytes = CborSerializationUtil.serialize(txWitnessesArray);
        byte[] auxDataBytes = CborSerializationUtil.serialize(auxDataMap);
        byte[] invalidTxsBytes = CborSerializationUtil.serialize(invalidTxsArray);

        byte[] h1 = Blake2bUtil.blake2bHash256(txBodiesBytes);
        byte[] h2 = Blake2bUtil.blake2bHash256(txWitnessesBytes);
        byte[] h3 = Blake2bUtil.blake2bHash256(auxDataBytes);
        byte[] h4 = Blake2bUtil.blake2bHash256(invalidTxsBytes);
        byte[] combined = new byte[128];
        System.arraycopy(h1, 0, combined, 0, 32);
        System.arraycopy(h2, 0, combined, 32, 32);
        System.arraycopy(h3, 0, combined, 64, 32);
        System.arraycopy(h4, 0, combined, 96, 32);
        byte[] bodyHash = Blake2bUtil.blake2bHash256(combined);
        long bodySize = txBodiesBytes.length + txWitnessesBytes.length + auxDataBytes.length + invalidTxsBytes.length;

        return new BlockBodyResult(txBodiesArray, txWitnessesArray, auxDataMap, invalidTxsArray, bodySize, bodyHash);
    }

    /**
     * Build the header array: [[header_body_fields...], signature]
     * Post-Babbage header body fields (per BlockHeaderSerializer.postBabbageHeader):
     * [blockNumber, slot, prevHash, issuerVkey, vrfVkey, vrfResult,
     * blockBodySize, blockBodyHash, operationalCert, protocolVersion]
     */
    protected Array buildHeaderArray(long blockNumber, long slot, byte[] prevHash,
                                   long bodySize, byte[] bodyHash) {
        Array headerBody = new Array();

        // 0: blockNumber
        headerBody.add(new UnsignedInteger(blockNumber));
        // 1: slot
        headerBody.add(new UnsignedInteger(slot));
        // 2: prevHash (null for genesis block 0)
        if (prevHash == null) {
            headerBody.add(SimpleValue.NULL);
        } else {
            headerBody.add(new ByteString(prevHash));
        }
        // 3: issuerVkey
        headerBody.add(new ByteString(new byte[VKEY_LENGTH]));
        // 4: vrfVkey
        headerBody.add(new ByteString(new byte[VRF_VKEY_LENGTH]));
        // 5: vrfResult [output, proof]
        Array vrfResult = new Array();
        vrfResult.add(new ByteString(new byte[VRF_OUTPUT_LENGTH]));
        vrfResult.add(new ByteString(new byte[VRF_PROOF_LENGTH]));
        headerBody.add(vrfResult);
        // 6: blockBodySize
        headerBody.add(new UnsignedInteger(bodySize));
        // 7: blockBodyHash
        headerBody.add(new ByteString(bodyHash));
        // 8: operationalCert [hotVKey, sequenceNumber, kesPeriod, sigma]
        Array opCert = new Array();
        opCert.add(new ByteString(new byte[VKEY_LENGTH]));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new UnsignedInteger(0));
        opCert.add(new ByteString(new byte[OPCERT_SIGMA_LENGTH]));
        headerBody.add(opCert);
        // 9: protocolVersion [major, minor]
        Array protoVersion = new Array();
        protoVersion.add(new UnsignedInteger(PROTOCOL_MAJOR));
        protoVersion.add(new UnsignedInteger(PROTOCOL_MINOR));
        headerBody.add(protoVersion);

        // Header array: [header_body, signature]
        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(new byte[KES_SIGNATURE_LENGTH]));

        return headerArray;
    }

    /**
     * Split a complete transaction CBOR into the parallel block arrays.
     * Each tx is CBOR-encoded as: [body, witnesses, is_valid, aux_data]
     */
    protected void splitTransaction(byte[] txCbor, int index,
                                  Array txBodiesArray, Array txWitnessesArray,
                                  Map auxDataMap) {
        try {
            DataItem txDI = CborSerializationUtil.deserializeOne(txCbor);
            Array txArray = (Array) txDI;
            List<DataItem> items = txArray.getDataItems();

            // tx_body (index 0)
            txBodiesArray.add(items.get(0));

            // witnesses (index 1)
            txWitnessesArray.add(items.get(1));

            // is_valid (index 2) - if false, add to invalid_txs
            // For now, assume all txs are valid

            // aux_data (index 3) - add to map if not null
            if (items.size() > 3 && items.get(3).getMajorType() != MajorType.SPECIAL) {
                auxDataMap.put(new UnsignedInteger(index), items.get(3));
            }
        } catch (Exception e) {
            log.warn("Failed to split transaction at index {}: {}", index, e.getMessage());
            // Add empty placeholders to maintain alignment
            txBodiesArray.add(new Map()); // empty tx body
            txWitnessesArray.add(new Map()); // empty witnesses
        }
    }
}
