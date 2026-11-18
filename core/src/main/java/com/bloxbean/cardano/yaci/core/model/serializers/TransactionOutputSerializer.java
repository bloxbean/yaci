package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.client.api.util.AssetUtil;
import com.bloxbean.cardano.client.transaction.raw.RawMultiAsset;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.client.transaction.raw.RawScript;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.StringUtil;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.bloxbean.cardano.yaci.core.util.Constants.LOVELACE;
import static com.bloxbean.cardano.yaci.core.util.StringUtil.sanitize;

/** Maps outputs read by {@link RawOutput} to Yaci's model (ADR 0003 D8). */
@Slf4j
public enum TransactionOutputSerializer implements Serializer<TransactionOutput> {
    INSTANCE;

    /**
     * @deprecated A decoded output has lost its source encoding. Use {@link #read(RawOutput)}, or
     * {@link BlockSerializer} for a block.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public TransactionOutput deserializeDI(DataItem dataItem) {
        return read(RawOutput.tolerant(CborSerializationUtil.span(dataItem)));
    }

    /**
     * @param output an output as received
     * @return the output
     */
    public TransactionOutput read(RawOutput output) {
        return TransactionOutput.builder()
                .address(output.addressBytes().map(TransactionOutputSerializer::address).orElse(null))
                .amounts(amounts(output))
                .datumHash(output.datumHash().map(HexUtil::encodeHexString).orElse(null))
                .inlineDatum(output.inlineDatumBytes().map(HexUtil::encodeHexString).orElse(null))
                .scriptRef(output.scriptRefBytes().map(HexUtil::encodeHexString).orElse(null))
                .inlineDatumHash(inlineDatumHash(output))
                .referenceScriptHash(referenceScriptHash(output))
                .build();
    }

    /*
     * Both hashes cover bytes inside a tag 24, which a re-encoding of the output keeps, so a decoded output gets them
     * too. Bytes that are not a datum or a script leave the hash null, logged; the output and its block are kept, as
     * they were before the hashes.
     */
    private static String inlineDatumHash(RawOutput output) {
        try {
            return output.inlineDatum().map(datum -> HexUtil.encodeHexString(datum.hash())).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Inline datum is not hashed: {}", e.getMessage());
            return null;
        }
    }

    private static String referenceScriptHash(RawOutput output) {
        Optional<RawScript> script;
        try {
            script = output.scriptRef();
        } catch (RuntimeException e) {
            log.warn("Reference script is not hashed: {}", e.getMessage());
            return null;
        }
        return script.map(WitnessesSerializer::hash).orElse(null);
    }

    private static String address(byte[] bytes) {
        try {
            return AddressUtil.bytesToAddress(bytes);
        } catch (Exception e) {
            throw new CborRuntimeException("Bytes cannot be converted to bech32 address", e);
        }
    }

    private static List<Amount> amounts(RawOutput output) {
        List<Amount> amounts = new ArrayList<>();
        output.amount().ifPresent(value -> {
            amounts.add(Amount.builder().unit(LOVELACE).assetName(LOVELACE).quantity(value.coin()).build());
            amounts.addAll(assets(value.multiAssets()));
        });
        return amounts;
    }

    /**
     * @param multiAssets the assets of a value or a mint
     * @return an amount per asset, in order
     */
    static List<Amount> assets(List<RawMultiAsset> multiAssets) {
        List<Amount> amounts = new ArrayList<>();
        for (RawMultiAsset multiAsset : multiAssets) {
            String policyId = HexUtil.encodeHexString(multiAsset.policyId());
            for (RawMultiAsset.Asset asset : multiAsset.assets()) {
                byte[] name = asset.name();
                String hexName = HexUtil.encodeHexString(name, false);
                String assetName = StringUtil.isUtf8(name) ? new String(name, StandardCharsets.UTF_8)
                        : AssetUtil.calculateFingerPrint(policyId, hexName);
                amounts.add(Amount.builder()
                        .unit(policyId + "." + hexName)
                        .policyId(policyId)
                        .assetName(sanitize(assetName))
                        .assetNameBytes(name)
                        .quantity(asset.quantity())
                        .build());
            }
        }
        return amounts;
    }
}
