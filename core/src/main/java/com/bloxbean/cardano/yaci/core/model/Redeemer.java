package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
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

    public static Redeemer deserialize(Array redeemerDI)
            throws CborDeserializationException, CborException {
        List<DataItem> redeemerDIList = redeemerDI.getDataItems();
        if (redeemerDIList == null || redeemerDIList.size() != 4) {
            throw new CborDeserializationException(
                    "Redeemer deserialization error. Invalid no of DataItems");
        }

        DataItem tagDI = redeemerDIList.get(0);
        DataItem indexDI = redeemerDIList.get(1);
        DataItem dataDI = redeemerDIList.get(2);
        DataItem exUnitDI = redeemerDIList.get(3);

        Redeemer redeemer = new Redeemer();

        // Tag
        int tagValue = ((UnsignedInteger) tagDI).getValue().intValue();
        if (tagValue == 0) {
            redeemer.setTag(RedeemerTag.Spend);
        } else if (tagValue == 1) {
            redeemer.setTag(RedeemerTag.Mint);
        } else if (tagValue == 2) {
            redeemer.setTag(RedeemerTag.Cert);
        } else if (tagValue == 3) {
            redeemer.setTag(RedeemerTag.Reward);
        }

        // Index
        redeemer.setIndex(((UnsignedInteger) indexDI).getValue().intValue());

        // Redeemer data
        redeemer.setData(Datum.from(dataDI));

        // Redeemer resource usage (ExUnits)
        redeemer.setExUnits(ExUnits.deserialize((Array) exUnitDI));

        //cbor
        redeemer.setCbor(HexUtil.encodeHexString(CborSerializationUtil.serialize(redeemerDI, false)));

        return redeemer;
    }
}
