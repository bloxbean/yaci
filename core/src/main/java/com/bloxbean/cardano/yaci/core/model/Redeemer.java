package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.*;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder = true)
public class Redeemer {
    private RedeemerTag tag;
    private int index;
    private Datum data;
    private ExUnits exUnits;
    private String cbor;

    public static Redeemer deserializePreConway(Array redeemerDI) {
        List<DataItem> redeemerDIList = redeemerDI.getDataItems();
        if (redeemerDIList == null || redeemerDIList.size() != 4) {
            throw new CborRuntimeException(
                    "Redeemer deserialization error. Invalid no of DataItems");
        }

        DataItem tagDI = redeemerDIList.get(0);
        DataItem indexDI = redeemerDIList.get(1);
        DataItem dataDI = redeemerDIList.get(2);
        DataItem exUnitDI = redeemerDIList.get(3);

        return getRedeemer(redeemerDI, (UnsignedInteger) tagDI, (UnsignedInteger) indexDI, dataDI, (Array) exUnitDI);
    }

    @SneakyThrows
    public static Redeemer deserialize(Array keyDI, Array valueDI) { //Conway era
        List<DataItem> keyDIList = keyDI.getDataItems();
        List<DataItem> valueDIList = valueDI.getDataItems();

        if (keyDIList == null || keyDIList.size() != 2) {
            throw new CborRuntimeException(
                    "Redeemer deserialization error. Invalid no of DataItems in key");
        }

        if (valueDIList == null || valueDIList.size() != 2) {
            throw new CborRuntimeException(
                    "Redeemer deserialization error. Invalid no of DataItems in value");
        }

        DataItem tagDI = keyDIList.get(0);
        DataItem indexDI = keyDIList.get(1);
        DataItem dataDI = valueDIList.get(0);
        DataItem exUnitDI = valueDIList.get(1);

        //TODO -- Cbor hex is a hack. It creates an array with all fields and then serializes it.
        //This is added to make it work with the existing code in client applications. Need to fix this.
        Array redeemerDI = new Array();
        redeemerDI.add(tagDI);
        redeemerDI.add(indexDI);
        redeemerDI.add(dataDI);
        redeemerDI.add(exUnitDI);

        return getRedeemer(redeemerDI, (UnsignedInteger) tagDI, (UnsignedInteger) indexDI, dataDI, (Array) exUnitDI);

    }

    @SneakyThrows
    private static Redeemer getRedeemer(Array redeemerDI, UnsignedInteger tagDI, UnsignedInteger indexDI,
                                        DataItem dataDI, Array exUnitDI) {
        Redeemer redeemer = new Redeemer();

        // Tag
        int tagValue = tagDI.getValue().intValue();
        if (tagValue == 0) {
            redeemer.setTag(RedeemerTag.Spend);
        } else if (tagValue == 1) {
            redeemer.setTag(RedeemerTag.Mint);
        } else if (tagValue == 2) {
            redeemer.setTag(RedeemerTag.Cert);
        } else if (tagValue == 3) {
            redeemer.setTag(RedeemerTag.Reward);
        } else if (tagValue == 4) {
            redeemer.setTag(RedeemerTag.Voting);
        } else if (tagValue == 5) {
            redeemer.setTag(RedeemerTag.Proposing);
        }

        // Index
        redeemer.setIndex(indexDI.getValue().intValue());

        // Redeemer data
        redeemer.setData(Datum.from(dataDI));

        // Redeemer resource usage (ExUnits)
        redeemer.setExUnits(ExUnits.deserialize(exUnitDI));

        //cbor
        redeemer.setCbor(HexUtil.encodeHexString(CborSerializationUtil.serialize(redeemerDI, false)));
        return redeemer;
    }
}
