package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.util.Tuple;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant.PROTOCOL_V13;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Getter
@AllArgsConstructor
@ToString
public class CurrentProtocolParamsQuery implements EraQuery<CurrentProtocolParamQueryResult> {
    @NonNull
    private Era era;

    public CurrentProtocolParamsQuery() {
        era = Era.Babbage;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        //[0 [0 [Era query]]]
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(3));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public CurrentProtocolParamQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        if (protocolVersion.getVersionNumber() <= PROTOCOL_V13) {
            return deserializeResultProtocolV13(di);
        } else {
            return deserializeResult(di);
        }
    }

    public CurrentProtocolParamQueryResult deserializeResultProtocolV13(DataItem[] di) {
        List<DataItem> dataItemList = ((Array) di[0]).getDataItems();

        int type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> paramsDIList = ((Array) extractResultArray(di[0]).get(0)).getDataItems();

        DataItem itemDI = paramsDIList.get(0);
        Integer minFeeA = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(1);
        Integer minFeeB = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(2);
        Integer maxBlockSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(3);
        Integer maxTxSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(4);
        Integer maxBlockHeaderSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(5);
        BigInteger keyDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(6);
        BigInteger poolDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(7);
        Integer maxEpoch = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(8);
        Integer nOpt = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(9);
        BigDecimal poolPledgeInfluence = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(10);
        BigDecimal expansionRate = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(11);
        BigDecimal treasuryGrowthRate = itemDI != null ? toRationalNumber(itemDI) : null;

//        itemDI = paramsDIList.get(12);
//        BigDecimal decentralizationParam = itemDI != null? toRationalNumber(itemDI): null;

//        String extraEntropy = null;
////                $nonce /= [ 0 // 1, bytes .size 32 ]
//        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(13)); //Removed
//        if (itemDI != null) {
//            List<DataItem> extraEntropyDIList =((Array) itemDI).getDataItems();
//            int extraEntropy_1 = toInt(extraEntropyDIList.get(0));
//            String extraEntropy_2 = "";
//            if (extraEntropyDIList.size() == 2) {
//                extraEntropy_2 = HexUtil.encodeHexString(toBytes(extraEntropyDIList.get(1)));
//            }
//            extraEntropy = List.of(extraEntropy_1, extraEntropy_2).toString();
//        }
        itemDI = paramsDIList.get(12);
        Integer protocolMajorVersion = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(13); //Removed
        Integer protocolMinorVersion = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(14);
        BigInteger minPoolCost = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(15);
        BigInteger adaPerUtxoBytes = itemDI != null ? toBigInteger(itemDI) : null;

        //CostModels
        java.util.Map<Integer, String> costModelMap = null;
        itemDI = paramsDIList.get(16);
        if (itemDI != null) {
            costModelMap = new LinkedHashMap<>();
            Map itemDIMap = (Map) itemDI;
            for (DataItem key : itemDIMap.getKeys()) {
                Integer version = toInt(key);
                String costModel = HexUtil.encodeHexString(CborSerializationUtil.serialize(itemDIMap.get(key)));
                costModelMap.put(version, costModel);
            }
        }

        //exUnits prices
        BigDecimal priceMem = null;
        BigDecimal priceSteps = null;
        itemDI = paramsDIList.get(17);
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<BigDecimal, BigDecimal> tuple = getExUnitPrices(exUnitPriceList);
            priceMem = tuple._1;
            priceSteps = tuple._2;
        }

        //max tx exunits
        BigInteger maxTxExMem = null;
        BigInteger maxTxExSteps = null;
        itemDI = paramsDIList.get(18);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxTxExMem = tuple._1;
            maxTxExSteps = tuple._2;
        }

        //max block exunits
        BigInteger maxBlockExMem = null;
        BigInteger maxBlockExSteps = null;
        itemDI = paramsDIList.get(19);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxBlockExMem = tuple._1;
            maxBlockExSteps = tuple._2;
        }

        itemDI = paramsDIList.get(20);
        Long maxValueSize = itemDI != null ? toLong(itemDI) : null;

        itemDI = paramsDIList.get(21);
        Integer collateralPercent = itemDI != null ? toInt(itemDI) : null;

        Integer maxCollateralInputs = null;
        if (paramsDIList.size() > 22) { //It seems node is returning 22 elements in first DI and 1 in second DI
            itemDI = paramsDIList.get(22);
            maxCollateralInputs = itemDI != null ? toInt(itemDI) : null;
        } else {
            if (di.length == 2) { //Check the second di in the array if available
                itemDI = di[1];
                maxCollateralInputs = itemDI != null ? toInt(itemDI) : null;
            }
        }

        ProtocolParamUpdate protocolParams = ProtocolParamUpdate.builder()
                .minFeeA(minFeeA)
                .minFeeB(minFeeB)
                .maxBlockSize(maxBlockSize)
                .maxTxSize(maxTxSize)
                .maxBlockHeaderSize(maxBlockHeaderSize)
                .keyDeposit(keyDeposit)
                .poolDeposit(poolDeposit)
                .maxEpoch(maxEpoch)
                .nOpt(nOpt)
                .poolPledgeInfluence(poolPledgeInfluence)
                .expansionRate(expansionRate)
                .treasuryGrowthRate(treasuryGrowthRate)
                .protocolMajorVer(protocolMajorVersion)
                .protocolMinorVer(protocolMinorVersion)
                .minPoolCost(minPoolCost)
                .adaPerUtxoByte(adaPerUtxoBytes)
                .costModels(costModelMap)
                .priceMem(priceMem)
                .priceStep(priceSteps)
                .maxTxExMem(maxTxExMem)
                .maxTxExSteps(maxTxExSteps)
                .maxBlockExMem(maxBlockExMem)
                .maxBlockExSteps(maxBlockExSteps)
                .maxValSize(maxValueSize)
                .collateralPercent(collateralPercent)
                .maxCollateralInputs(maxCollateralInputs)
                .build();

        return new CurrentProtocolParamQueryResult(protocolParams);
    }


    public CurrentProtocolParamQueryResult deserializeResult(DataItem[] di) {
        List<DataItem> dataItemList = ((Array) di[0]).getDataItems();

        int type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> paramsDIList = ((Array) extractResultArray(di[0]).get(0)).getDataItems();

        DataItem itemDI = paramsDIList.get(0);
        Integer minFeeA = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(1);
        Integer minFeeB = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(2);
        Integer maxBlockSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(3);
        Integer maxTxSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(4);
        Integer maxBlockHeaderSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(5);
        BigInteger keyDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(6);
        BigInteger poolDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(7);
        Integer maxEpoch = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(8);
        Integer nOpt = itemDI != null ? toInt(itemDI) : null;

        itemDI = paramsDIList.get(9);
        BigDecimal poolPledgeInfluence = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(10);
        BigDecimal expansionRate = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = paramsDIList.get(11);
        BigDecimal treasuryGrowthRate = itemDI != null ? toRationalNumber(itemDI) : null;

        Integer protocolMajorVersion = null;
        Integer protocolMinorVersion = null;
        itemDI = paramsDIList.get(12);
        if (itemDI != null) {
            List<DataItem> protocolVersions = ((Array) itemDI).getDataItems();
            protocolMajorVersion = toInt(protocolVersions.get(0));
            protocolMinorVersion = toInt(protocolVersions.get(1));
        }

        itemDI = paramsDIList.get(13);
        BigInteger minPoolCost = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = paramsDIList.get(14);
        BigInteger adaPerUtxoBytes = itemDI != null ? toBigInteger(itemDI) : null;

        //CostModels
        java.util.Map<Integer, String> costModelMap = null;
        itemDI = paramsDIList.get(15);
        if (itemDI != null) {
            costModelMap = new LinkedHashMap<>();
            Map itemDIMap = (Map) itemDI;
            for (DataItem key : itemDIMap.getKeys()) {
                Integer version = toInt(key);
                String costModel = HexUtil.encodeHexString(CborSerializationUtil.serialize(itemDIMap.get(key)));
                costModelMap.put(version, costModel);
            }
        }

        //exUnits prices
        BigDecimal priceMem = null;
        BigDecimal priceSteps = null;
        itemDI = paramsDIList.get(16);
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<BigDecimal, BigDecimal> tuple = getExUnitPrices(exUnitPriceList);
            priceMem = tuple._1;
            priceSteps = tuple._2;
        }

        //max tx exunits
        BigInteger maxTxExMem = null;
        BigInteger maxTxExSteps = null;
        itemDI = paramsDIList.get(17);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxTxExMem = tuple._1;
            maxTxExSteps = tuple._2;
        }

        //max block exunits
        BigInteger maxBlockExMem = null;
        BigInteger maxBlockExSteps = null;
        itemDI = paramsDIList.get(18);
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxBlockExMem = tuple._1;
            maxBlockExSteps = tuple._2;
        }

        itemDI = paramsDIList.get(19);
        Long maxValueSize = itemDI != null ? toLong(itemDI) : null;

        itemDI = paramsDIList.get(20);
        Integer collateralPercent = itemDI != null ? toInt(itemDI) : null;

        Integer maxCollateralInputs = null;
        itemDI = paramsDIList.get(21);
        maxCollateralInputs = itemDI != null ? toInt(itemDI) : null;

        ProtocolParamUpdate protocolParams = ProtocolParamUpdate.builder()
                .minFeeA(minFeeA)
                .minFeeB(minFeeB)
                .maxBlockSize(maxBlockSize)
                .maxTxSize(maxTxSize)
                .maxBlockHeaderSize(maxBlockHeaderSize)
                .keyDeposit(keyDeposit)
                .poolDeposit(poolDeposit)
                .maxEpoch(maxEpoch)
                .nOpt(nOpt)
                .poolPledgeInfluence(poolPledgeInfluence)
                .expansionRate(expansionRate)
                .treasuryGrowthRate(treasuryGrowthRate)
                .protocolMajorVer(protocolMajorVersion)
                .protocolMinorVer(protocolMinorVersion)
                .minPoolCost(minPoolCost)
                .adaPerUtxoByte(adaPerUtxoBytes)
                .costModels(costModelMap)
                .priceMem(priceMem)
                .priceStep(priceSteps)
                .maxTxExMem(maxTxExMem)
                .maxTxExSteps(maxTxExSteps)
                .maxBlockExMem(maxBlockExMem)
                .maxBlockExSteps(maxBlockExSteps)
                .maxValSize(maxValueSize)
                .collateralPercent(collateralPercent)
                .maxCollateralInputs(maxCollateralInputs)
                .build();

        return new CurrentProtocolParamQueryResult(protocolParams);
    }

    private Tuple<BigInteger, BigInteger> getExUnits(List<DataItem> exunits) {
        BigInteger mem = toBigInteger(exunits.get(0));

        BigInteger steps = null;
        if (exunits.size() > 1)
            steps = toBigInteger(exunits.get(1));

        return new Tuple<>(mem, steps);
    }

    private Tuple<BigDecimal, BigDecimal> getExUnitPrices(List<DataItem> exunits) {
        RationalNumber memPriceRN = (RationalNumber) exunits.get(0);
        RationalNumber stepPriceRN = (RationalNumber) exunits.get(1);

        BigDecimal memPrice = toRationalNumber(memPriceRN);
        BigDecimal stepPrice = toRationalNumber(stepPriceRN);

        return new Tuple<>(memPrice, stepPrice);
    }
}
