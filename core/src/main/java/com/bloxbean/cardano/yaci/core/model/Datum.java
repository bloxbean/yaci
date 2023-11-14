package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.*;

import java.util.Objects;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder(toBuilder = true)
public class Datum {
    private String hash;
    private String cbor;
    private String json;

    public static Datum from(DataItem plutusDataDI)
            throws CborDeserializationException, CborException {
        PlutusData plutusData = PlutusData.deserialize(plutusDataDI);
        if (Objects.isNull(plutusData)) {
            return null;
        }

        var cbor = CborSerializationUtil.serialize(plutusDataDI, false);
        var datumHash = HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(cbor));
        return Datum.builder()
                .hash(datumHash)
                .cbor(HexUtil.encodeHexString(cbor))
                .json(JsonUtil.getPrettyJson(plutusData))
                .build();
    }

    public static String cborToHash(byte[] cborByte) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(cborByte));
    }

}
