package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.AssetUtil;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.client.common.CardanoConstants.LOVELACE;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Getter
@AllArgsConstructor
public class UtxoByAddressQuery implements EraQuery<UtxoByAddressQueryResult> {
    private Era era;
    private Address address;

    public UtxoByAddressQuery(Address address) {
        this(Era.Alonzo, address);
    }

    @Override
    public DataItem serialize() {
        Array array = new Array();
        array.add(new UnsignedInteger(6));

        //address
        Array addArr = new Array();
//        addArr.setChunked(true);
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
            //key is utxo = [hash idx datum_hash?]
            List<DataItem> keyDIList = ((Array) key).getDataItems();
            String txHash = toHex(keyDIList.get(0));
            int index = toInt(keyDIList.get(1));
            String dataHash = null;
            if (keyDIList.size() > 2)
                dataHash = toHex(keyDIList.get(2));

            Map valueMap = (Map) utxoMap.get(key);
            List<Amount> amountList = new ArrayList<>();

            // String assetName = toHex(valueMap.get(new UnsignedInteger(0)));
            BigInteger coin = null;
            DataItem valueDI = valueMap.get(new UnsignedInteger(1));
            if (valueDI.getMajorType() == MajorType.UNSIGNED_INTEGER) { //only coin
                coin = toBigInteger(valueDI);
            } else if (valueDI.getMajorType() == MajorType.ARRAY) {
                Value value = Value.deserialize((Array) valueDI);

                coin = value.getCoin();

                for (MultiAsset ma : value.getMultiAssets()) {
                    ma.getAssets().forEach(asset -> {
                        Amount assetAmount = new Amount(AssetUtil.getUnit(ma.getPolicyId(), asset), asset.getValue());
                        amountList.add(assetAmount);
                    });
                }
            }

            Amount coinAmt = new Amount(LOVELACE, coin);
            amountList.add(0, coinAmt);

            Utxo utxo = Utxo.builder()
                    .txHash(txHash)
                    .outputIndex(index)
                    .amount(amountList)
                    .dataHash(dataHash)
                    .build();
            utxoList.add(utxo);
        }

        return new UtxoByAddressQueryResult(utxoList);
    }

}
