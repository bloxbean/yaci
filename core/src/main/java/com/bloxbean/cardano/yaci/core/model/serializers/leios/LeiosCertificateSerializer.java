package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.leios.LeiosCertificate;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.protocol.leios.serializers.LeiosCborUtil;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.List;

/**
 * Decodes the w27 Dijkstra Leios certificate while preserving the exact raw CBOR item.
 */
public enum LeiosCertificateSerializer implements Serializer<LeiosCertificate> {
    INSTANCE;

    @Override
    public LeiosCertificate deserialize(byte[] bytes) {
        String cbor = HexUtil.encodeHexString(bytes);
        try {
            DataItem dataItem = CborSerializationUtil.deserializeOne(bytes);
            if (!(dataItem instanceof Array certificateArray)) {
                return rawOnly(cbor);
            }

            List<DataItem> items = LeiosCborUtil.arrayItems(certificateArray, "Leios certificate");
            if (items.size() != 2) {
                return rawOnly(cbor);
            }
            if (!(items.get(0) instanceof ByteString signers)
                    || !(items.get(1) instanceof ByteString aggregatedSignature)
                    || aggregatedSignature.getBytes().length != 48) {
                return rawOnly(cbor);
            }

            return LeiosCertificate.builder()
                    .cbor(cbor)
                    .signers(HexUtil.encodeHexString(signers.getBytes()))
                    .aggregatedSignature(HexUtil.encodeHexString(aggregatedSignature.getBytes()))
                    .build();
        } catch (Exception e) {
            return rawOnly(cbor);
        }
    }

    @Override
    public LeiosCertificate deserializeDI(DataItem di) {
        return deserialize(CborSerializationUtil.serialize(di, false));
    }

    private LeiosCertificate rawOnly(String cbor) {
        return LeiosCertificate.builder()
                .cbor(cbor)
                .build();
    }
}
