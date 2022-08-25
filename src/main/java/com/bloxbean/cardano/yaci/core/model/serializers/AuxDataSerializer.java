package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum AuxDataSerializer implements Serializer<AuxData> {
    INSTANCE;

    @Override
    public AuxData deserializeDI(DataItem di) {
        try {
            AuxiliaryData auxiliaryData = AuxiliaryData.deserialize((Map) di);
            Metadata metadata = auxiliaryData.getMetadata();

            String metadataCbor = null;
            String metadataJson = null;
            if (metadata != null) {
                try {
                    metadataJson = MetadataToJsonNoSchemaConverter.cborBytesToJson(metadata.serialize());
                } catch (Exception e) {
                    log.error("Error converting metadata cbor to json", e);
                }

                metadataCbor = HexUtil.encodeHexString(metadata.serialize());
            }

            return new AuxData(metadataCbor, metadataJson);

        } catch (CborDeserializationException e) {
            throw new CborRuntimeException("AuxiliaryData deserialization failed", e);
        }
    }
}
