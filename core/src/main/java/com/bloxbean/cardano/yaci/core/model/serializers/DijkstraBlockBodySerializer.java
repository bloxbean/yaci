package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.yaci.core.common.EraUtil;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosEndorserBlockRef;
import com.bloxbean.cardano.yaci.core.model.serializers.leios.LeiosCertificateSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor;
import com.bloxbean.cardano.yaci.core.model.serializers.util.DijkstraTransactionExtractor.TransactionSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Dijkstra (Leios) ranking block into Yaci's existing {@link Block} model.
 * See {@link DijkstraTransactionExtractor} for the wire layout.
 * <p>
 * Each transaction gets the same treatment {@link BlockSerializer} gives Shelley through Conway blocks:
 * transaction bodies keep their original bytes (so tx hashes are correct), datum/redeemer bytes are taken from
 * the source, and a witness set or metadata that cannot be parsed normally (too deeply nested, or bad JSON) is
 * recovered with {@link DataItemIsolation}. Recovery is per transaction, so one bad item never affects the rest.
 */
@Slf4j
public enum DijkstraBlockBodySerializer {
    INSTANCE;

    /**
     * True when the block's era tag is Dijkstra. Reads only the era tag; any decoding problem returns false so
     * the caller's original path handles the block exactly as before.
     */
    static boolean isDijkstraBlock(byte[] blockBytes) {
        try {
            return EraUtil.getEra(DataItemIsolation.unsigned(CborSlice.arrayItem(blockBytes, 0))) == Era.Dijkstra;
        } catch (Exception e) {
            return false;
        }
    }

    @SneakyThrows
    public Block deserialize(byte[] blockBytes) {
        DijkstraTransactionExtractor.BlockBodySlice slice = DijkstraTransactionExtractor.extractBlockBody(blockBytes);
        BlockHeader header = BlockHeaderSerializer.INSTANCE.getBlockHeaderFromHeaderArray(
                (Array) CborSerializationUtil.deserializeOne(slice.headerBytes()));
        long blockNumber = header.getHeaderBody().getBlockNumber();
        if (slice.extraItems() > 0) {
            log.debug("Ignoring {} extra Dijkstra block_body item(s) at block {}", slice.extraItems(), blockNumber);
        }

        ParsedTransactions txs = parseTransactions(blockNumber, slice.transactions());

        Block.BlockBuilder blockBuilder = Block.builder()
                .era(Era.Dijkstra)
                .header(header)
                .transactionBodies(txs.bodies())
                .transactionWitness(txs.witnesses())
                .auxiliaryDataMap(txs.auxiliaryData())
                .invalidTransactions(txs.invalidIndexes());
        if (slice.leiosCertificateBytes() != null) {
            blockBuilder.leiosCertificate(LeiosCertificateSerializer.INSTANCE.deserialize(slice.leiosCertificateBytes()));
        }
        if (slice.perasCertificateBytes() != null) {
            blockBuilder.perasCertCbor(HexUtil.encodeHexString(slice.perasCertificateBytes()));
        }
        if (YaciConfig.INSTANCE.isReturnBlockCbor()) {
            blockBuilder.cbor(HexUtil.encodeHexString(blockBytes));
        }
        return blockBuilder.build();
    }

    /**
     * Returns a copy of a certifying block whose transaction lists start with the endorser block's transactions
     * (in EB order), followed by the block's own transactions. Auxiliary-data keys and invalid-transaction indexes
     * of the block's own transactions move up by the number of EB transactions. The header, certificate and raw
     * block CBOR stay as received.
     *
     * @param certifyingBlock    the block as parsed from the wire
     * @param endorserBlock      the EB it certifies; its {@code txCount} is set from {@code ebTransactionBytes}
     * @param ebTransactionBytes each EB transaction's bytes as served by LeiosFetch, in EB order
     */
    @SneakyThrows
    public Block mergeEndorserBlockTransactions(Block certifyingBlock, LeiosEndorserBlockRef endorserBlock,
                                                List<byte[]> ebTransactionBytes) {
        long blockNumber = certifyingBlock.getHeader().getHeaderBody().getBlockNumber();
        List<TransactionSlice> slices = new ArrayList<>(ebTransactionBytes.size());
        for (int i = 0; i < ebTransactionBytes.size(); i++) {
            slices.add(DijkstraTransactionExtractor.readEndorserBlockTransaction(i, ebTransactionBytes.get(i)));
        }
        ParsedTransactions eb = parseTransactions(blockNumber, slices);
        int n = slices.size();

        List<TransactionBody> bodies = new ArrayList<>(eb.bodies());
        if (certifyingBlock.getTransactionBodies() != null) bodies.addAll(certifyingBlock.getTransactionBodies());
        List<Witnesses> witnesses = new ArrayList<>(eb.witnesses());
        if (certifyingBlock.getTransactionWitness() != null) witnesses.addAll(certifyingBlock.getTransactionWitness());
        Map<Integer, AuxData> auxiliaryData = new LinkedHashMap<>(eb.auxiliaryData());
        if (certifyingBlock.getAuxiliaryDataMap() != null) {
            certifyingBlock.getAuxiliaryDataMap().forEach((index, aux) -> auxiliaryData.put(index + n, aux));
        }
        List<Integer> invalid = new ArrayList<>(eb.invalidIndexes());
        if (certifyingBlock.getInvalidTransactions() != null) {
            certifyingBlock.getInvalidTransactions().forEach(index -> invalid.add(index + n));
        }

        return Block.builder()
                .era(certifyingBlock.getEra())
                .header(certifyingBlock.getHeader())
                .transactionBodies(bodies)
                .transactionWitness(witnesses)
                .auxiliaryDataMap(auxiliaryData)
                .invalidTransactions(invalid)
                .leiosCertificate(certifyingBlock.getLeiosCertificate())
                .perasCertCbor(certifyingBlock.getPerasCertCbor())
                .leiosEndorserBlock(endorserBlock.toBuilder().txCount(n).build())
                .cbor(certifyingBlock.getCbor())
                .build();
    }

    /**
     * Per-transaction parsing shared by ranking blocks and endorser blocks. Indexes come from the slices.
     */
    private ParsedTransactions parseTransactions(long blockNumber, List<TransactionSlice> slices) {
        List<TransactionBody> transactionBodies = new ArrayList<>();
        List<Witnesses> witnessesSet = new ArrayList<>();
        List<byte[]> witnessRawBytes = new ArrayList<>();
        Map<Integer, AuxData> auxiliaryDataMap = new LinkedHashMap<>();
        List<Integer> invalidTransactions = new ArrayList<>();

        for (TransactionSlice tx : slices) {
            transactionBodies.add(TransactionBodySerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(tx.bodyBytes()), tx.bodyBytes(), true));
            witnessesSet.add(witnesses(tx.witnessesBytes()));
            witnessRawBytes.add(tx.witnessesBytes());
            if (tx.auxiliaryDataBytes() != null) {
                auxiliaryDataMap.put(tx.index(), auxiliaryData(blockNumber, tx, transactionBodies));
            }
            if (!tx.valid()) {
                invalidTransactions.add(tx.index());
            }
        }

        if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
            BlockSerializer.INSTANCE.setWitnessCbor(blockNumber, witnessesSet, witnessRawBytes);
        }
        //To fix #37 (datum/redeemer hash after a CBOR round trip): take their bytes from the source
        BlockSerializer.INSTANCE.handleWitnessDatumRedeemer(blockNumber, witnessesSet, witnessRawBytes);
        return new ParsedTransactions(transactionBodies, witnessesSet, auxiliaryDataMap, invalidTransactions);
    }

    private record ParsedTransactions(List<TransactionBody> bodies, List<Witnesses> witnesses,
                                      Map<Integer, AuxData> auxiliaryData, List<Integer> invalidIndexes) {
    }

    Witnesses witnesses(byte[] raw) {
        try {
            Witnesses witnesses = WitnessesSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(raw));
            return DataItemIsolation.hasDataError(witnesses) ? DataItemIsolation.witness(raw) : witnesses;
        } catch (StackOverflowError e) {
            return DataItemIsolation.witness(raw);
        }
    }

    private AuxData auxiliaryData(long blockNumber, TransactionSlice tx, List<TransactionBody> transactionBodies) {
        return auxiliaryData(blockNumber, tx.index(), transactionBodies, tx.auxiliaryDataBytes());
    }

    AuxData auxiliaryData(long blockNumber, int index, List<TransactionBody> transactionBodies, byte[] raw) {
        AuxData auxData;
        try {
            auxData = AuxDataSerializer.INSTANCE.deserializeDI(CborSerializationUtil.deserializeOne(raw));
            if (auxData.getMetadataParseError() != null) {
                auxData = DataItemIsolation.auxiliary(raw);
            }
        } catch (StackOverflowError e) {
            auxData = DataItemIsolation.auxiliary(raw);
        }
        if (YaciConfig.INSTANCE.isReturnFullTxCbor()
                && BlockSerializer.INSTANCE.isAuxDataHashValid(blockNumber, index, transactionBodies, raw)) {
            auxData = auxData.toBuilder().cbor(HexUtil.encodeHexString(raw)).build();
        }
        return auxData;
    }
}
