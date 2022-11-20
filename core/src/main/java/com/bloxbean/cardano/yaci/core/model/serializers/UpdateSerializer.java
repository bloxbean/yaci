package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.util.Tuple;
import com.bloxbean.cardano.yaci.core.model.ProtocolParamUpdate;
import com.bloxbean.cardano.yaci.core.model.Update;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

public enum UpdateSerializer implements Serializer<Update> {
    INSTANCE;

    //    update = [ proposed_protocol_parameter_updates
//         , epoch
//         ]
    @Override
    public Update deserializeDI(DataItem di) {
        Array array = (Array) di;
        List<DataItem> dataItemList = array.getDataItems();

        long epoch = toInt(dataItemList.get(1));

        Map protoParamUpdateMap = (Map) dataItemList.get(0);

        java.util.Map<String, ProtocolParamUpdate> deProtocolParamUpdateMap = new HashMap<>();
        Collection<DataItem> genesisHashDIKeys = protoParamUpdateMap.getKeys();

        if (genesisHashDIKeys != null && genesisHashDIKeys.size() > 0) {
            for (DataItem gensisHashDI : genesisHashDIKeys) {
                Map genesisProtocolParamsMap = (Map) protoParamUpdateMap.get(gensisHashDI);

                ProtocolParamUpdate protocolParamUpdate = getProtocolParams(genesisProtocolParamsMap);

                String genHashKey = HexUtil.encodeHexString(toBytes(gensisHashDI));
                deProtocolParamUpdateMap.put(genHashKey, protocolParamUpdate);
            }
        }

        return new Update(deProtocolParamUpdateMap, epoch);
    }

    public ProtocolParamUpdate getProtocolParams(Map genesisProtocolParamsMap) {
        DataItem itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(0));
        Integer minFeeA = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(1));
        Integer minFeeB = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(2));
        Integer maxBlockSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(3));
        Integer maxTxSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(4));
        Integer maxBlockHeaderSize = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(5));
        BigInteger keyDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(6));
        BigInteger poolDeposit = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(7));
        Integer maxEpoch = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(8));
        Integer nOpt = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(9));
        BigDecimal poolPledgeInfluence = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(10));
        BigDecimal expansionRate = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(11));
        BigDecimal treasuryGrowthRate = itemDI != null ? toRationalNumber(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(12));
        BigDecimal decentralizationParam = itemDI != null ? toRationalNumber(itemDI) : null;

        String extraEntropy = null;
//                $nonce /= [ 0 // 1, bytes .size 32 ]
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(13)); //Removed
        if (itemDI != null) {
            List<DataItem> extraEntropyDIList = ((Array) itemDI).getDataItems();
            int extraEntropy_1 = toInt(extraEntropyDIList.get(0));
            String extraEntropy_2 = "";
            if (extraEntropyDIList.size() == 2) {
                extraEntropy_2 = HexUtil.encodeHexString(toBytes(extraEntropyDIList.get(1)));
            }
            extraEntropy = List.of(extraEntropy_1, extraEntropy_2).toString();
        }

        Integer protocolMajorVersion = null;
        Integer protocolMinorVersion = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(14));
        if (itemDI != null) {
            List<DataItem> protocolVersionDIList = ((Array) itemDI).getDataItems();
            protocolMajorVersion = toInt(protocolVersionDIList.get(0));
            protocolMinorVersion = toInt(protocolVersionDIList.get(1));
        }

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(15)); //Removed
        BigInteger minUtxo = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(16));
        BigInteger minPoolCost = itemDI != null ? toBigInteger(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(17));
        BigInteger adaPerUtxoBytes = itemDI != null ? toBigInteger(itemDI) : null;

        //CostModels
        java.util.Map<Integer, String> costModelMap = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(18));
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
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(19));
        if (itemDI != null) {
            List<DataItem> exUnitPriceList = ((Array) itemDI).getDataItems();
            Tuple<BigDecimal, BigDecimal> tuple = getExUnitPrices(exUnitPriceList);
            priceMem = tuple._1;
            priceSteps = tuple._2;
        }

        //max tx exunits
        BigInteger maxTxExMem = null;
        BigInteger maxTxExSteps = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(20));
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxTxExMem = tuple._1;
            maxTxExSteps = tuple._2;
        }

        //max block exunits
        BigInteger maxBlockExMem = null;
        BigInteger maxBlockExSteps = null;
        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(21));
        if (itemDI != null) {
            List<DataItem> exUnits = ((Array) itemDI).getDataItems();
            Tuple<BigInteger, BigInteger> tuple = getExUnits(exUnits);
            maxBlockExMem = tuple._1;
            maxBlockExSteps = tuple._2;
        }

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(22));
        Long maxValueSize = itemDI != null ? toLong(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(23));
        Integer collateralPercent = itemDI != null ? toInt(itemDI) : null;

        itemDI = genesisProtocolParamsMap.get(new UnsignedInteger(24));
        Integer maxCollateralPercent = itemDI != null ? toInt(itemDI) : null;

        ProtocolParamUpdate protocolParamUpdate = ProtocolParamUpdate.builder()
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
                .decentralisationParam(decentralizationParam)
                .extraEntropy(extraEntropy)
                .protocolMajorVer(protocolMajorVersion)
                .protocolMinorVer(protocolMinorVersion)
                .minUtxo(minUtxo)
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
                .maxCollateralInputs(maxCollateralPercent)
                .build();
        return protocolParamUpdate;
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
