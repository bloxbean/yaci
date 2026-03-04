package com.bloxbean.cardano.yaci.node.runtime.utxo;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CBOR codec for UTXO storage values.
 * Keys:
 * 0 addr(bstr), 1 lovelace(uint), 2 assets([pol(28), name(bstr), qty(uint)]),
 * 3 datumHash(bstr32, opt), 4 inlineDatum(bstr, opt), 5 scriptRef(bstr, opt),
 * 6 referenceScriptHash(bstr28, opt), 7 isCollateralReturn(bool),
 * 8 slot(uint), 9 blockNumber(uint), 10 blockHash(bstr32)
 */
final class UtxoCborCodec {
    private UtxoCborCodec() {}

    static byte[] encodeUtxoRecord(String addressBech32,
                                   BigInteger lovelace,
                                   List<Amount> assets,
                                   String datumHash,
                                   byte[] inlineDatum,
                                   String scriptRefHex,
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
        if (scriptRefHex != null) map.put(new UnsignedInteger(5), new ByteString(HexUtil.decodeHexString(scriptRefHex)));
        if (referenceScriptHash != null) map.put(new UnsignedInteger(6), new ByteString(referenceScriptHash));
        map.put(new UnsignedInteger(7), collateralReturn ? SimpleValue.TRUE : SimpleValue.FALSE);
        map.put(new UnsignedInteger(8), new UnsignedInteger(slot));
        map.put(new UnsignedInteger(9), new UnsignedInteger(blockNumber));
        if (blockHashHex != null) map.put(new UnsignedInteger(10), new ByteString(HexUtil.decodeHexString(blockHashHex)));

        return CborSerializationUtil.serialize(map, true);
    }

    static StoredUtxo decodeUtxoRecord(byte[] bytes) {
        Map map = (Map) CborSerializationUtil.deserializeOne(bytes);
        String address = new String(((ByteString) map.get(new UnsignedInteger(0))).getBytes());
        BigInteger lovelace = CborSerializationUtil.toBigInteger(map.get(new UnsignedInteger(1)));
        // Assets
        java.util.List<com.bloxbean.cardano.yaci.core.model.Amount> assets = new java.util.ArrayList<>();
        DataItem d2 = map.get(new UnsignedInteger(2));
        if (d2 instanceof Array arr) {
            for (DataItem di : arr.getDataItems()) {
                Array a = (Array) di;
                String pol = HexUtil.encodeHexString(((ByteString) a.getDataItems().get(0)).getBytes());
                byte[] nameBytes = ((ByteString) a.getDataItems().get(1)).getBytes();
                java.math.BigInteger qty = CborSerializationUtil.toBigInteger(a.getDataItems().get(2));
                assets.add(new com.bloxbean.cardano.yaci.core.model.Amount(null, pol, null, nameBytes, qty));
            }
        }
        // Datum hash and inline datum
        String datumHash = null;
        DataItem d3 = map.get(new UnsignedInteger(3));
        if (d3 instanceof ByteString bs) datumHash = HexUtil.encodeHexString(bs.getBytes());
        byte[] inlineDatum = null;
        DataItem d4 = map.get(new UnsignedInteger(4));
        if (d4 instanceof ByteString bs4) inlineDatum = bs4.getBytes();
        boolean collateral = SimpleValue.TRUE.equals(map.get(new UnsignedInteger(7)));
        long slot = CborSerializationUtil.toLong(map.get(new UnsignedInteger(8)));
        long blockNumber = CborSerializationUtil.toLong(map.get(new UnsignedInteger(9)));
        String blockHash = null;
        DataItem d10 = map.get(new UnsignedInteger(10));
        if (d10 instanceof ByteString bs10) blockHash = HexUtil.encodeHexString(bs10.getBytes());

        return new StoredUtxo(address, lovelace, assets, datumHash, inlineDatum, collateral, slot, blockNumber, blockHash);
    }

    static class StoredUtxo {
        final String address;
        final BigInteger lovelace;
        final java.util.List<com.bloxbean.cardano.yaci.core.model.Amount> assets;
        final String datumHash;
        final byte[] inlineDatum;
        final boolean collateralReturn;
        final long slot;
        final long blockNumber;
        final String blockHash;

        StoredUtxo(String address, BigInteger lovelace,
                   java.util.List<com.bloxbean.cardano.yaci.core.model.Amount> assets,
                   String datumHash, byte[] inlineDatum,
                   boolean collateralReturn, long slot, long blockNumber, String blockHash) {
            this.address = address;
            this.lovelace = lovelace;
            this.assets = assets;
            this.datumHash = datumHash;
            this.inlineDatum = inlineDatum;
            this.collateralReturn = collateralReturn;
            this.slot = slot;
            this.blockNumber = blockNumber;
            this.blockHash = blockHash;
        }
    }
}
