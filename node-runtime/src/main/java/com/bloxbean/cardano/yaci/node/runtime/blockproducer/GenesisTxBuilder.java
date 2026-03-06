package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds CBOR-encoded genesis transactions from initial fund allocations.
 * <p>
 * Each genesis transaction uses a dummy zero-hash input (genesis marker) and
 * produces outputs in post-Alonzo format for each funded address.
 * <p>
 * If more than {@link #MAX_OUTPUTS_PER_TX} addresses are provided,
 * the funds are split across multiple transactions.
 */
@Slf4j
public class GenesisTxBuilder {

    static final int MAX_OUTPUTS_PER_TX = 100;
    private static final int GENESIS_HASH_LENGTH = 32;

    /**
     * Build genesis transactions from initial funds.
     *
     * @param initialFunds map of hex-encoded address bytes to lovelace amount
     * @return list of complete transaction CBOR bytes (each is [body, witnesses, is_valid, aux_data])
     */
    public static List<byte[]> buildGenesisTransactions(Map<String, Long> initialFunds) {
        if (initialFunds == null || initialFunds.isEmpty()) {
            return List.of();
        }

        List<byte[]> transactions = new ArrayList<>();
        List<Map.Entry<String, Long>> entries = new ArrayList<>(initialFunds.entrySet());

        for (int batchStart = 0; batchStart < entries.size(); batchStart += MAX_OUTPUTS_PER_TX) {
            int batchEnd = Math.min(batchStart + MAX_OUTPUTS_PER_TX, entries.size());
            List<Map.Entry<String, Long>> batch = entries.subList(batchStart, batchEnd);
            int txIndex = batchStart / MAX_OUTPUTS_PER_TX;

            byte[] txCbor = buildSingleGenesisTx(batch, txIndex);
            transactions.add(txCbor);
        }

        log.info("Built {} genesis transaction(s) for {} funded addresses",
                transactions.size(), initialFunds.size());
        return transactions;
    }

    /**
     * Build a single genesis transaction with the given outputs.
     *
     * @param outputs list of (hex address, lovelace) entries
     * @param txIndex transaction index (used for the dummy input index)
     */
    private static byte[] buildSingleGenesisTx(List<Map.Entry<String, Long>> outputs, int txIndex) {
        // tx_body (CBOR map): {0: inputs, 1: outputs, 2: fee}
        co.nstant.in.cbor.model.Map txBody = new co.nstant.in.cbor.model.Map();

        // inputs: [[h'0000...00', txIndex]] — dummy genesis marker
        Array inputs = new Array();
        Array input = new Array();
        input.add(new ByteString(new byte[GENESIS_HASH_LENGTH])); // 32-byte zero hash
        input.add(new UnsignedInteger(txIndex));
        inputs.add(input);
        txBody.put(new UnsignedInteger(0), inputs);

        // outputs: post-Alonzo format [{0: h'<addr_bytes>', 1: <lovelace>}, ...]
        Array outputsArray = new Array();
        for (Map.Entry<String, Long> entry : outputs) {
            co.nstant.in.cbor.model.Map output = new co.nstant.in.cbor.model.Map();
            byte[] addrBytes = HexUtil.decodeHexString(entry.getKey());
            output.put(new UnsignedInteger(0), new ByteString(addrBytes));
            output.put(new UnsignedInteger(1), new UnsignedInteger(entry.getValue()));
            outputsArray.add(output);
        }
        txBody.put(new UnsignedInteger(1), outputsArray);

        // fee: 0 (genesis transactions are fee-free)
        txBody.put(new UnsignedInteger(2), new UnsignedInteger(0));

        // witnesses: empty map
        co.nstant.in.cbor.model.Map witnesses = new co.nstant.in.cbor.model.Map();

        // Build complete tx: [body, witnesses, is_valid, aux_data]
        Array tx = new Array();
        tx.add(txBody);
        tx.add(witnesses);
        tx.add(SimpleValue.TRUE);
        tx.add(SimpleValue.NULL);

        return CborSerializationUtil.serialize(tx);
    }
}
