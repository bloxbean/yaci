package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.crypto.BlockProducerKeys;
import com.bloxbean.cardano.client.crypto.kes.OpCert;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * Block builder that produces cryptographically valid Conway-era blocks
 * with real VRF proofs, KES signatures, and operational certificates.
 * <p>
 * Extends {@link DevnetBlockBuilder} to reuse transaction-splitting logic.
 * When key files are not configured, the system falls back to the parent class
 * which uses dummy zero-filled crypto material.
 */
@Slf4j
public class SignedBlockBuilder extends DevnetBlockBuilder {

    private final BlockProducerKeys keys;
    private final BlockSigner blockSigner;
    private final EpochNonceState epochNonceState;
    private final NonceStateStore nonceStore; // nullable

    private final long slotsPerKESPeriod;
    private final long maxKESEvolutions;

    // Derived from keys
    private final byte[] issuerVkey;   // 32 bytes — cold verification key from opcert
    private final byte[] vrfVkey;      // 32 bytes — last 32 bytes of VRF skey

    /**
     * @param keys               loaded VRF, KES, and OpCert keys
     * @param slotsPerKESPeriod  from shelley-genesis.json
     * @param maxKESEvolutions   from shelley-genesis.json
     * @param epochNonceState    initialized nonce state
     * @param nonceStore         optional persistence (null for in-memory only)
     */
    public SignedBlockBuilder(BlockProducerKeys keys, long slotsPerKESPeriod, long maxKESEvolutions,
                              EpochNonceState epochNonceState, NonceStateStore nonceStore) {
        this(keys, slotsPerKESPeriod, maxKESEvolutions, epochNonceState, nonceStore, 10, 2);
    }

    public SignedBlockBuilder(BlockProducerKeys keys, long slotsPerKESPeriod, long maxKESEvolutions,
                              EpochNonceState epochNonceState, NonceStateStore nonceStore,
                              long protocolMajor, long protocolMinor) {
        super(protocolMajor, protocolMinor);
        this.keys = keys;
        this.blockSigner = new BlockSigner();
        this.epochNonceState = epochNonceState;
        this.nonceStore = nonceStore;
        this.slotsPerKESPeriod = slotsPerKESPeriod;
        this.maxKESEvolutions = maxKESEvolutions;

        // Derive keys
        this.issuerVkey = keys.getOpCert().getColdVkey();
        this.vrfVkey = Arrays.copyOfRange(keys.getVrfSkey(), 32, 64);

        log.info("SignedBlockBuilder initialized: issuerVkey={}, vrfVkey={}, slotsPerKESPeriod={}, maxKESEvolutions={}",
                HexUtil.encodeHexString(issuerVkey), HexUtil.encodeHexString(vrfVkey),
                slotsPerKESPeriod, maxKESEvolutions);
    }

    @Override
    public BlockBuildResult buildBlock(long blockNumber, long slot, byte[] prevHash,
                                       List<byte[]> transactions) {
        // 1. Compute block body
        BlockBodyResult body = computeBlockBody(transactions);

        // 2. Advance epoch FIRST (performs TICKN if crossing epoch boundary)
        epochNonceState.advanceEpochIfNeeded(slot);

        // 3. Get epoch nonce (now correct for this slot's epoch)
        byte[] epochNonce = epochNonceState.getEpochNonce();

        // 4. Compute VRF proof with correct nonce
        BlockSigner.VrfSignResult vrfResult = blockSigner.computeVrf(keys.getVrfSkey(), slot, epochNonce);

        // 5. Build header body CBOR array
        Array headerBody = buildSignedHeaderBody(blockNumber, slot, prevHash,
                body.bodySize(), body.bodyHash(), vrfResult);

        // 6. Serialize header body for KES signing
        byte[] headerBodyCborBytes = CborSerializationUtil.serialize(headerBody);

        // 7. Compute KES period and sign
        int kesPeriod = (int) (slot / slotsPerKESPeriod);
        int opcertKesPeriod = (int) keys.getOpCert().getKesPeriod();
        int relativePeriod = kesPeriod - opcertKesPeriod;
        if (relativePeriod < 0 || relativePeriod >= maxKESEvolutions) {
            throw new IllegalStateException(
                    "KES period out of range: current=" + kesPeriod + ", opcert=" + opcertKesPeriod
                            + ", relative=" + relativePeriod + ", max=" + maxKESEvolutions);
        }

        byte[] kesSig = blockSigner.signHeaderBody(keys.getKesSkey(), headerBodyCborBytes,
                kesPeriod, opcertKesPeriod);

        // 8. Assemble header: [headerBody, kesSig]
        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(kesSig));

        // 9. Compute block hash, assemble full block and wrapped header
        BlockBuildResult result = assembleBlock(headerArray, body, blockNumber, slot,
                transactions != null ? transactions.size() : 0);

        // 10. Evolve nonce state
        epochNonceState.onBlockProduced(slot, prevHash, vrfResult.output());

        // 11. Persist nonce state
        if (nonceStore != null) {
            nonceStore.storeEpochNonceState(epochNonceState.serialize());
        }

        return result;
    }

    /**
     * Build the signed header body as a 10-element CBOR array.
     */
    private Array buildSignedHeaderBody(long blockNumber, long slot, byte[] prevHash,
                                         long bodySize, byte[] bodyHash,
                                         BlockSigner.VrfSignResult vrfResult) {
        OpCert opCert = keys.getOpCert();
        Array headerBody = new Array();

        // 0: blockNumber
        headerBody.add(new UnsignedInteger(blockNumber));
        // 1: slot
        headerBody.add(new UnsignedInteger(slot));
        // 2: prevHash
        if (prevHash == null) {
            headerBody.add(SimpleValue.NULL);
        } else {
            headerBody.add(new ByteString(prevHash));
        }
        // 3: issuerVkey (32 bytes)
        headerBody.add(new ByteString(issuerVkey));
        // 4: vrfVkey (32 bytes)
        headerBody.add(new ByteString(vrfVkey));
        // 5: vrfResult [output(64), proof(80)]
        Array vrfArray = new Array();
        vrfArray.add(new ByteString(vrfResult.output())); // 64 bytes
        vrfArray.add(new ByteString(vrfResult.proof()));   // 80 bytes
        headerBody.add(vrfArray);
        // 6: bodySize
        headerBody.add(new UnsignedInteger(bodySize));
        // 7: bodyHash
        headerBody.add(new ByteString(bodyHash));
        // 8: operationalCert [kesVkey(32), counter, kesPeriod, coldSignature(64)]
        Array opCertArray = new Array();
        opCertArray.add(new ByteString(opCert.getKesVkey()));
        opCertArray.add(new UnsignedInteger(opCert.getCounter()));
        opCertArray.add(new UnsignedInteger(opCert.getKesPeriod()));
        opCertArray.add(new ByteString(opCert.getColdSignature()));
        headerBody.add(opCertArray);
        // 9: protocolVersion [major, minor]
        Array protoVersion = new Array();
        protoVersion.add(new UnsignedInteger(protocolMajor));
        protoVersion.add(new UnsignedInteger(protocolMinor));
        headerBody.add(protoVersion);

        return headerBody;
    }
}
