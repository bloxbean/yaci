package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.List;

final class UtxoDeltaCodec {
    private UtxoDeltaCodec() {}

    static byte[] encode(long blockNumber, long slot, String blockHash,
                         List<OutRef> created, List<OutRef> spent) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new UnsignedInteger(blockNumber));
        map.put(new UnsignedInteger(1), new UnsignedInteger(slot));
        if (blockHash != null) map.put(new UnsignedInteger(2), new ByteString(HexUtil.decodeHexString(blockHash)));
        Array c = new Array();
        for (OutRef r : created) c.add(encodeOut(r));
        Array s = new Array();
        for (OutRef r : spent) s.add(encodeOut(r));
        map.put(new UnsignedInteger(3), c);
        map.put(new UnsignedInteger(4), s);
        return CborSerializationUtil.serialize(map, true);
    }

    static Decoded decode(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        long blockNumber = CborSerializationUtil.toLong(map.get(new UnsignedInteger(0)));
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(1)));
        List<OutRef> created = decodeList((Array) map.get(new UnsignedInteger(3)));
        List<OutRef> spent = decodeList((Array) map.get(new UnsignedInteger(4)));
        return new Decoded(blockNumber, slot, created, spent);
    }

    private static Array encodeOut(OutRef r) {
        Array a = new Array();
        a.add(new ByteString(HexUtil.decodeHexString(r.txHash())));
        a.add(new UnsignedInteger(r.index()));
        return a;
    }

    private static List<OutRef> decodeList(Array arr) {
        List<OutRef> list = new ArrayList<>();
        if (arr == null) return list;
        for (DataItem di : arr.getDataItems()) {
            Array a = (Array) di;
            String tx = HexUtil.encodeHexString(((ByteString) a.getDataItems().get(0)).getBytes());
            int idx = CborSerializationUtil.toInt(a.getDataItems().get(1));
            list.add(new OutRef(tx, idx));
        }
        return list;
    }

    record OutRef(String txHash, int index) {}
    record Decoded(long blockNumber, long slot, List<OutRef> created, List<OutRef> spent) {}
}

