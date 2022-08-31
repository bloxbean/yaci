package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.helper.MetadataToJsonNoSchemaConverter;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public enum AuxDataSerializer implements Serializer<AuxData> {
    INSTANCE;

    @Override
    public AuxData deserializeDI(DataItem di) {
        try {
            Metadata metadata = null;
            List<NativeScript> nativeScripts = null;
            List<PlutusScript> plutusV1scripts = null;
            List<PlutusScript> plutusV2scripts = null;

            if (di.getMajorType() == MajorType.MAP) { //Shelley + Alonzo
                AuxiliaryData auxiliaryData = AuxiliaryData.deserialize((Map) di);
                metadata = auxiliaryData.getMetadata();

                if (auxiliaryData.getPlutusV1Scripts() != null) {
                    plutusV1scripts = auxiliaryData.getPlutusV1Scripts().stream()
                            .map(plutusV1Script -> new PlutusScript(String.valueOf(plutusV1Script.getScriptType()), plutusV1Script.getCborHex()))
                            .collect(Collectors.toList());
                }

                if (auxiliaryData.getPlutusV2Scripts() != null) {
                    plutusV2scripts = auxiliaryData.getPlutusV2Scripts().stream()
                            .map(plutusV2Script -> new PlutusScript(String.valueOf(plutusV2Script.getScriptType()), plutusV2Script.getCborHex()))
                            .collect(Collectors.toList());
                }

            } else if (di.getMajorType() == MajorType.ARRAY) { //Shelley ma era. Handle it here as it's not handled in cardano-client-lib
                List<DataItem> auxDIList = ((Array) di).getDataItems();
                DataItem metadataDI = auxDIList.get(0);
                AuxiliaryData auxiliaryData = AuxiliaryData.deserialize((Map)metadataDI);
                metadata = auxiliaryData.getMetadata();

                Array auxiliaryScriptsArray = (Array)auxDIList.get(1);
                if (auxiliaryScriptsArray != null && auxiliaryScriptsArray.getDataItems().size() > 0) {
                    nativeScripts = new ArrayList<>();
                    for (DataItem auxScriptDI : auxiliaryScriptsArray.getDataItems()) {
                        if (auxScriptDI == SimpleValue.BREAK)
                            continue;
                        nativeScripts.add(WintessesSerializer.INSTANCE.deserializeNativeScript((Array) auxScriptDI));
                    }
                }
            }

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

            return new AuxData(metadataCbor, metadataJson, nativeScripts, plutusV1scripts, plutusV2scripts);

        } catch (CborDeserializationException e) {
            throw new CborRuntimeException("AuxiliaryData deserialization failed", e);
        }
    }
}
