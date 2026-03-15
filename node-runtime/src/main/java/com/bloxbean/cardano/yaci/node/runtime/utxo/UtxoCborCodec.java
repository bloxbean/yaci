package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CBOR codec for UTXO storage values.
 * Keys:
 * 0 addr(bstr), 1 lovelace(uint), 2 assets([pol(28), name(bstr), qty(uint)]),
 * 3 datumHash(bstr32, opt), 4 inlineDatum(bstr, opt),
 * 5 referenceScriptHash(bstr28, opt), 6 isCollateralReturn(bool),
 * 7 slot(uint), 8 blockNumber(uint), 9 blockHash(bstr32)
 */
final class UtxoCborCodec {
    private UtxoCborCodec() {}

    static byte[] encodeUtxoRecord(String addressBech32,
                                   BigInteger lovelace,
                                   List<Amount> assets,
                                   String datumHash,
                                   byte[] inlineDatum,
                                   byte[] referenceScriptHash,
                                   boolean collateralReturn,
                                   long slot,
                                   long blockNumber,
                                   String blockHashHex) {
        Map map = new Map();
        map.put(new UnsignedInteger(0), new ByteString(addressBech32.getBytes()));
        map.put(new UnsignedInteger(1), new UnsignedInteger(lovelace));
        Array assetsArr = new Array();
        if (assets != null) {
            for (Amount a : assets) {
                if (a.getPolicyId() == null) continue; // skip lovelace here
                Array item = new Array();
                item.add(new ByteString(HexUtil.decodeHexString(a.getPolicyId())));
                byte[] name = a.getAssetNameBytes() != null ? a.getAssetNameBytes() : new byte[0];
                item.add(new ByteString(name));
                item.add(new UnsignedInteger(a.getQuantity()));
                assetsArr.add(item);
            }
        }
        map.put(new UnsignedInteger(2), assetsArr);

        if (datumHash != null) map.put(new UnsignedInteger(3), new ByteString(HexUtil.decodeHexString(datumHash)));
        if (inlineDatum != null) map.put(new UnsignedInteger(4), new ByteString(inlineDatum));
        if (referenceScriptHash != null) map.put(new UnsignedInteger(5), new ByteString(referenceScriptHash));
        map.put(new UnsignedInteger(6), collateralReturn ? SimpleValue.TRUE : SimpleValue.FALSE);
        map.put(new UnsignedInteger(7), new UnsignedInteger(slot));
        map.put(new UnsignedInteger(8), new UnsignedInteger(blockNumber));
        if (blockHashHex != null) map.put(new UnsignedInteger(9), new ByteString(HexUtil.decodeHexString(blockHashHex)));

        return CborSerializationUtil.serialize(map, true);
    }

    /**
     * Unwrap the original UTXO record bytes from a spent record.
     * Spent records are CBOR maps with key 1 = spent slot, key 2 = original UTXO.
     */
    static byte[] unwrapSpentUtxo(byte[] spentRecordBytes) {
        Map m = (Map) CborSerializationUtil.deserializeOne(spentRecordBytes);
        DataItem di = m.get(new UnsignedInteger(2));
        return CborSerializationUtil.serialize(di, true);
    }

    /**
     * Unwrap and decode the original UTXO directly from a spent record,
     * avoiding a serialize-then-deserialize round trip.
     */
    static StoredUtxo decodeSpentUtxoRecord(byte[] spentRecordBytes) {
        Map m = (Map) CborSerializationUtil.deserializeOne(spentRecordBytes);
        DataItem di = m.get(new UnsignedInteger(2));
        return decodeUtxoRecordFromDataItem((Map) di);
    }

    static StoredUtxo decodeUtxoRecord(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        return decodeUtxoRecordFromDataItem(map);
    }

    private static StoredUtxo decodeUtxoRecordFromDataItem(Map map) {
        String address = new String(((ByteString) map.get(new UnsignedInteger(0))).getBytes());
        BigInteger lovelace = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(1)));
        // Assets
        List<Amount> assets = new ArrayList<>();
        DataItem d2 = map.get(new UnsignedInteger(2));
        if (d2 instanceof Array arr) {
            for (DataItem di : arr.getDataItems()) {
                Array a = (Array) di;
                String pol = HexUtil.encodeHexString(((ByteString) a.getDataItems().get(0)).getBytes());
                byte[] nameBytes = ((ByteString) a.getDataItems().get(1)).getBytes();
                BigInteger qty = CborSerializationUtil.toBigInteger(a.getDataItems().get(2));
                assets.add(new Amount(null, pol, null, nameBytes, qty));
            }
        }
        // Datum hash and inline datum
        String datumHash = null;
        DataItem d3 = map.get(new UnsignedInteger(3));
        if (d3 instanceof ByteString bs) datumHash = HexUtil.encodeHexString(bs.getBytes());

        byte[] inlineDatum = null;
        DataItem d4 = map.get(new UnsignedInteger(4));
        if (d4 instanceof ByteString bs4) inlineDatum = bs4.getBytes();

        String referenceScriptHash = null;
        DataItem d5 = map.get(new UnsignedInteger(5));
        if (d5 instanceof ByteString bs5) referenceScriptHash = HexUtil.encodeHexString(bs5.getBytes());

        boolean collateral = SimpleValue.TRUE.equals(map.get(new UnsignedInteger(6)));

        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(7)));
        long blockNumber = CborSerializationUtil.toLong(map.get(new UnsignedInteger(8)));

        String blockHash = null;
        DataItem d9 = map.get(new UnsignedInteger(9));
        if (d9 instanceof ByteString bs9) blockHash = HexUtil.encodeHexString(bs9.getBytes());

        return new StoredUtxo(address, lovelace, assets, datumHash, inlineDatum, referenceScriptHash, collateral, slot, blockNumber, blockHash);
    }

    static class StoredUtxo {
        final String address;
        final BigInteger lovelace;
        final List<Amount> assets;
        final String datumHash;
        final byte[] inlineDatum;
        final String referenceScriptHash;
        final boolean collateralReturn;
        final long slot;
        final long blockNumber;
        final String blockHash;

        StoredUtxo(String address, BigInteger lovelace,
                   List<Amount> assets,
                   String datumHash, byte[] inlineDatum,
                   String referenceScriptHash,
                   boolean collateralReturn, long slot, long blockNumber, String blockHash) {
            this.address = address;
            this.lovelace = lovelace;
            this.assets = assets;
            this.datumHash = datumHash;
            this.inlineDatum = inlineDatum;
            this.referenceScriptHash = referenceScriptHash;
            this.collateralReturn = collateralReturn;
            this.slot = slot;
            this.blockNumber = blockNumber;
            this.blockHash = blockHash;
        }
    }
}
