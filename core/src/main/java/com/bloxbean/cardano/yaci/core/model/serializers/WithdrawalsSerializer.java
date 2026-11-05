package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.RawWithdrawal;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps withdrawals read by {@link RawWithdrawal} to Yaci's model (ADR 0003 D8). */
public enum WithdrawalsSerializer implements Serializer<Map<String, BigInteger>> {
    INSTANCE;

    @Override
    public Map<String, BigInteger> deserializeDI(DataItem di) {
        return read(RawWithdrawal.tolerant(RawModels.span(di)));
    }

    Map<String, BigInteger> read(List<RawWithdrawal> withdrawals) {
        Map<String, BigInteger> withdrawalsMap = new LinkedHashMap<>();
        for (RawWithdrawal withdrawal : withdrawals)
            withdrawalsMap.put(HexUtil.encodeHexString(withdrawal.rewardAccount()), withdrawal.coin());
        return Collections.unmodifiableMap(withdrawalsMap);
    }
}
