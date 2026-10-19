package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.SubTransaction;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.model.serializers.TransactionBodySerializer.slices;

/**
 * Dijkstra era {@code sub_transactions = nonempty_oset<sub_transaction>} (tx body key 23), with
 * {@code sub_transaction = [sub_transaction_body, transaction_witness_set, auxiliary_data / nil]}.
 * Parsed from the original bytes, like a top-level transaction: each body's txHash is the hash of its bytes as
 * on chain, and witnesses and auxiliary data get the same handling as in a block.
 */
enum SubTransactionSerializer {
    INSTANCE;

    //Sub-transactions are not in a block's transaction list; only used in log messages
    private static final long NO_BLOCK = -1;

    List<SubTransaction> deserialize(CborSlice subTransactions) {
        List<SubTransaction> result = new ArrayList<>();
        for (CborSlice subTransaction : slices(subTransactions, MajorType.ARRAY)) { //with or without tag 258
            List<CborSlice> parts = slices(subTransaction, MajorType.ARRAY);
            byte[] bodyBytes = parts.get(0).bytes();
            byte[] witnessBytes = parts.get(1).bytes();
            byte[] auxBytes = parts.get(2).bytes();

            TransactionBody body = TransactionBodySerializer.INSTANCE.deserializeDI(
                    CborSerializationUtil.deserializeOne(bodyBytes), bodyBytes, true);

            List<Witnesses> witnesses = new ArrayList<>(List.of(DijkstraBlockBodySerializer.INSTANCE.witnesses(witnessBytes)));
            List<byte[]> witnessRawBytes = List.of(witnessBytes);
            if (YaciConfig.INSTANCE.isReturnFullTxCbor()) {
                BlockSerializer.INSTANCE.setWitnessCbor(NO_BLOCK, witnesses, witnessRawBytes);
            }
            BlockSerializer.INSTANCE.handleWitnessDatumRedeemer(NO_BLOCK, witnesses, witnessRawBytes);

            AuxData auxData = isNil(auxBytes) ? null
                    : DijkstraBlockBodySerializer.INSTANCE.auxiliaryData(NO_BLOCK, 0, List.of(body), auxBytes);

            result.add(new SubTransaction(body, witnesses.get(0), auxData));
        }
        return result;
    }

    private static boolean isNil(byte[] cbor) {
        return cbor.length == 1 && (cbor[0] & 0xff) == 0xf6;
    }
}
