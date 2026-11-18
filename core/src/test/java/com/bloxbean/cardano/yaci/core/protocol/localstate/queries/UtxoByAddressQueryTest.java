package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UtxoByAddressQueryTest {

    @Test
    void referenceScriptHashIsTheScriptsHashNotItsBytes() {
        // [4, [{[h'11..', 0]: {0: addr, 1: coin, 2: [1, 24(h'00')], 3: 24(h'820143010203')}}]]
        String output = "a4" + "00581d60" + "00".repeat(28) + "011a000f4240" + "028201d8184100"
                + "03d81846820143010203";
        String result = "82" + "04" + "81" + "a1" + "82" + "5820" + "11".repeat(32) + "00" + output;
        DataItem[] di = CborSerializationUtil.deserialize(HexUtil.decodeHexString(result));
        UtxoByAddressQuery query = new UtxoByAddressQuery(new Address(HexUtil.decodeHexString("60" + "00".repeat(28))));

        Utxo utxo = query.deserializeResult(null, di).getUtxoList().get(0);

        assertThat(utxo.getReferenceScriptHash()).isEqualTo("bc8f82996834417a91ad5f5bbf06a35e1fdbfe42f21ff91485f443f1");
        assertThat(utxo.getInlineDatum()).isEqualTo("00");
        assertThat(utxo.getTxHash()).isEqualTo("11".repeat(32));
    }
}
