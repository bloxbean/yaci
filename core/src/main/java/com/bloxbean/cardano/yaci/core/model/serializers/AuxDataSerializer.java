package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.cbor.CBORMetadata;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScript;
import com.bloxbean.cardano.yaci.core.model.PlutusScriptType;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

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
            List<PlutusScript> plutusV3scripts = null;

            if (di.getMajorType() == MajorType.MAP) {
                Tag mapTag = di.getTag();
                Map map = (Map) di;

                if (mapTag != null && mapTag.getValue() == 259) { //Alonzo and beyond
                    DataItem metadataValueDI = map.get(new UnsignedInteger(0));
                    DataItem nativeScriptsValueDI = map.get(new UnsignedInteger(1));
                    DataItem plutusV1ScriptsValueDI = map.get(new UnsignedInteger(2));
                    DataItem plutusV2ScriptsValueDI = map.get(new UnsignedInteger(3));
                    DataItem plutusV3ScriptsValueDI = map.get(new UnsignedInteger(4));

                    if (metadataValueDI != null) {
                        metadata = CBORMetadata.deserialize((Map) metadataValueDI);
                    }

                    //Native scripts
                    if (nativeScriptsValueDI != null) {
                        Array nativeScriptsArray = (Array) nativeScriptsValueDI;
                        nativeScripts = nativeScriptsArray.getDataItems()
                                .stream()
                                .map(nativeScriptDI -> WitnessesSerializer.INSTANCE.deserializeNativeScript((Array) nativeScriptDI))
                                .collect(Collectors.toList());
                    }

                    //plutus_v1_script
                    if (plutusV1ScriptsValueDI != null) {
                        Array plutusV1ScriptsArray = (Array) plutusV1ScriptsValueDI;
                        plutusV1scripts = plutusV1ScriptsArray.getDataItems()
                                .stream()
                                .map(plutusV1ScriptDI -> new PlutusScript(PlutusScriptType.PlutusScriptV1, toHex(plutusV1ScriptDI)))
                                .collect(Collectors.toList());
                    }

                    //plutus_v2_script
                    if (plutusV2ScriptsValueDI != null) {
                        Array plutusV2ScriptsArray = (Array) plutusV2ScriptsValueDI;
                        plutusV2scripts = plutusV2ScriptsArray.getDataItems()
                                .stream()
                                .map(plutusV2ScriptDI -> new PlutusScript(PlutusScriptType.PlutusScriptV2, toHex(plutusV2ScriptDI)))
                                .collect(Collectors.toList());
                    }

                    //plutus_v3_script
                    if (plutusV3ScriptsValueDI != null) {
                        Array plutusV3ScriptsArray = (Array) plutusV3ScriptsValueDI;
                        plutusV3scripts = plutusV3ScriptsArray.getDataItems()
                                .stream()
                                .map(plutusV3ScriptDI -> new PlutusScript(PlutusScriptType.PlutusScriptV3, toHex(plutusV3ScriptDI)))
                                .collect(Collectors.toList());
                    }

                } else { //shelley
                    metadata = CBORMetadata.deserialize(map);
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
                        nativeScripts.add(WitnessesSerializer.INSTANCE.deserializeNativeScript((Array) auxScriptDI));
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

            return new AuxData(metadataCbor, metadataJson, nativeScripts, plutusV1scripts, plutusV2scripts, plutusV3scripts);

        } catch (CborDeserializationException e) {
            throw new CborRuntimeException("AuxiliaryData deserialization failed", e);
        }
    }
}
