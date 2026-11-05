package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.BlockEnvelope;
import com.bloxbean.cardano.client.transaction.raw.RawHeader;
import com.bloxbean.cardano.client.transaction.raw.RawVrfCert;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.HeaderBody;
import com.bloxbean.cardano.yaci.core.model.OperationalCert;
import com.bloxbean.cardano.yaci.core.model.ProtocolVersion;
import com.bloxbean.cardano.yaci.core.model.VrfCert;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.function.Supplier;

/**
 * Maps CCL's {@link RawHeader}, which reads both header layouts, to Yaci's model. {@code blockHash} is the hash of the
 * header's original bytes (ADR 0003 D8), except from the deprecated {@code getBlockHeaderFromHeaderArray}, which has
 * only a decoded array and so hashes its encoding.
 */
public enum BlockHeaderSerializer implements Serializer<BlockHeader> {
    INSTANCE;

    @Override
    public BlockHeader deserialize(byte[] bytes) {
        DataItem di = CborSerializationUtil.deserializeOne(bytes);

        return deserializeDI(di);
    }

    @Override
    public BlockHeader deserializeDI(DataItem di) {
        byte[] headerBytes = ((ByteString) di).getBytes();
        return read(() -> first(headerBytes));
    }

    /**
     * @deprecated A decoded header has lost its source encoding, so its {@code blockHash} is that of a re-encoding.
     * Use {@link #deserializeDI(DataItem)} with the header's bytes, or {@link #deserializeHeaderOfBlock(byte[])}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public BlockHeader getBlockHeaderFromHeaderArray(Array headerArray) {
        return read(() -> CborSpan.of(CborSerializationUtil.serialize(headerArray)));
    }

    /**
     * Reads the header of a block without reading what follows it, so it works on a block damaged after its header.
     *
     * @param blockBytes a block in its envelope
     * @return the header
     */
    public BlockHeader deserializeHeaderOfBlock(byte[] blockBytes) {
        return read(() -> BlockEnvelope.headerOf(blockBytes));
    }

    /**
     * The first value of a CBOR sequence, as {@link CborSerializationUtil#deserializeOne(byte[])} returns: the values
     * after it are not used, but must be well-formed.
     */
    static CborSpan first(byte[] bytes) {
        try {
            return CborSlice.first(bytes).span();
        } catch (CborException e) {
            throw new CborRuntimeException("Cbor de-serialization error", e);
        }
    }

    private BlockHeader read(Supplier<CborSpan> header) {
        try {
            return map(RawHeader.of(header.get()));
        } catch (com.bloxbean.cardano.client.exception.CborRuntimeException e) {
            throw new CborRuntimeException("Invalid block header", e);
        }
    }

    BlockHeader map(RawHeader header) {
        HeaderBody.HeaderBodyBuilder body = HeaderBody.builder()
                .blockNumber(header.blockNumber())
                .slot(header.slot())
                .prevHash(header.prevHash().map(HexUtil::encodeHexString).orElse(null))
                .issuerVkey(hex(header.issuerVkey()))
                .vrfVkey(hex(header.vrfVkey()))
                .blockBodySize(header.bodySize())
                .blockBodyHash(hex(header.bodyHash()))
                .operationalCert(OperationalCert.builder()
                        .hotVKey(hex(header.hotVkey()))
                        .sequenceNumber((int) header.sequenceNumber())
                        .kesPeriod((int) header.kesPeriod())
                        .sigma(hex(header.sigma()))
                        .build())
                .protocolVersion(new ProtocolVersion(header.protocolMajor(), header.protocolMinor()))
                .blockHash(hex(header.hash()));
        header.vrfResult().ifPresent(vrf -> body.vrfResult(vrfCert(vrf)));
        header.nonceVrf().ifPresent(vrf -> body.nonceVrf(vrfCert(vrf)));
        header.leaderVrf().ifPresent(vrf -> body.leaderVrf(vrfCert(vrf)));
        return new BlockHeader(body.build(), hex(header.bodySignature()));
    }

    private static VrfCert vrfCert(RawVrfCert vrf) {
        return new VrfCert(hex(vrf.output()), hex(vrf.proof()));
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }
}
