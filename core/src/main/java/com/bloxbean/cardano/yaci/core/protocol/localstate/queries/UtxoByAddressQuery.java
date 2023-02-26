package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.serializers.TransactionOutputSerializer;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

@Getter
@AllArgsConstructor
public class UtxoByAddressQuery implements EraQuery<UtxoByAddressQueryResult> {
    private Era era;
    private Address address;

    public UtxoByAddressQuery(Address address) {
        this(Era.Babbage, address);
    }

    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(6));

        //address
        Array addArr = new Array();
        addArr.add(new ByteString(address.getBytes()));
        addArr.setTag(258);

        array.add(addArr);

        return wrapWithOuterArray(array);
    }

    @Override
    public UtxoByAddressQueryResult deserializeResult(DataItem[] di) {
        List<DataItem> utxoDIList = extractResultArray(di[0]);
        Map utxoMap = (Map) utxoDIList.get(0); //As only one address is passed, we are sure only one value in the list

        List<Utxo> utxoList = new ArrayList<>();
        for (DataItem key : utxoMap.getKeys()) {
            //key is utxo = [hash idx]
            List<DataItem> keyDIList = ((Array) key).getDataItems();
            String txHash = toHex(keyDIList.get(0));
            int index = toInt(keyDIList.get(1));

            TransactionOutput transactionOutput = TransactionOutputSerializer.INSTANCE.deserializeDI(utxoMap.get(key));
            List<Amount> amountList = transactionOutput.getAmounts().stream()
                    .map(amount -> new Amount(amount.getUnit(), amount.getQuantity()))
                    .collect(Collectors.toList());
            Utxo utxo = Utxo.builder()
                    .address(transactionOutput.getAddress())
                    .txHash(txHash)
                    .outputIndex(index)
                    .amount(amountList)
                    .dataHash(transactionOutput.getDatumHash())
                    .inlineDatum(transactionOutput.getInlineDatum())
                    .referenceScriptHash(transactionOutput.getScriptRef())
                    .build();
            utxoList.add(utxo);
        }

        return new UtxoByAddressQueryResult(utxoList);
    }
}
