package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.List;

@Getter
@AllArgsConstructor
@ToString
public class GenesisConfigQuery implements EraQuery<GenesisConfigQueryResult> {
    private Era era;

    @Override
    public DataItem serialize() {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(11));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public GenesisConfigQueryResult deserializeResult(DataItem[] di) {
        List<DataItem> dataItems = ((Array)extractResultArray(di[0]).get(0)).getDataItems();

        //System start [0]
        List<DataItem> systemStart = ((Array)dataItems.get(0)).getDataItems();
        int year = ((UnsignedInteger)systemStart.get(0)).getValue().intValue();
        int dayOfYear = ((UnsignedInteger)systemStart.get(1)).getValue().intValue();
        long picoSecondsOfDay = ((UnsignedInteger)systemStart.get(2)).getValue().intValue();

        long networkMagic = ((UnsignedInteger)dataItems.get(1)).getValue().longValue();
        long networkId = ((UnsignedInteger)dataItems.get(2)).getValue().longValue();
        double activeSlotsCoeff = arrayToDecimal((Array) dataItems.get(3));
        int securityParam = ((UnsignedInteger)dataItems.get(4)).getValue().intValue();
        int epochLength = ((UnsignedInteger)dataItems.get(5)).getValue().intValue();
        int slotsPerKesPeriod = ((UnsignedInteger)dataItems.get(6)).getValue().intValue();
        int maxKESEvolutions = ((UnsignedInteger)dataItems.get(7)).getValue().intValue();
        int slotLength = ((UnsignedInteger)dataItems.get(8)).getValue().intValue();
        int updateQuorum = ((UnsignedInteger)dataItems.get(9)).getValue().intValue();
        BigInteger maxLovelaceSupply = ((UnsignedInteger)dataItems.get(10)).getValue();

        //TODO shelley genesis protocol parameters 11
        //TODO shelley genesis delegation parameters 12
        //TODO shelley initial funds 13
        //TODO shelley genesis staking 14

        return GenesisConfigQueryResult.builder()
                .systemStartYear(year)
                .systemStartDayOfYear(dayOfYear)
                .systemStartPicoSecondsOfDay(picoSecondsOfDay)
                .networkMagic(networkMagic)
                .networkId(networkId)
                .activeSlotsCoeff(activeSlotsCoeff)
                .securityParam(securityParam)
                .epochLength(epochLength)
                .slotsPerKesPeriod(slotsPerKesPeriod)
                .maxKESEvolutions(maxKESEvolutions)
                .slotLength(slotLength)
                .updateQuorum(updateQuorum)
                .maxLovelaceSupply(maxLovelaceSupply)
                .build();
    }

    private double arrayToDecimal(Array array) {
        if (array.getDataItems().size() != 2)
            throw new IllegalArgumentException("Invalid array size. Expected 2 but got " + array.getDataItems().size());

        List<DataItem> dataItems = array.getDataItems();
        int numerator = ((UnsignedInteger)dataItems.get(0)).getValue().intValue();
        int denominator = ((UnsignedInteger)dataItems.get(1)).getValue().intValue();

        return numerator / (double)denominator;
    }

}
