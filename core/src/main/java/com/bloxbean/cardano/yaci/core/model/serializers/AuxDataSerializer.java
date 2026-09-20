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
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public enum AuxDataSerializer implements Serializer<AuxData> {
    INSTANCE;

    @Override
    public AuxData deserialize(byte[] bytes) {
        try {
            AuxData result = Serializer.super.deserialize(bytes);
            if (result == null) return null; // Preserve the default serializer's empty-input behavior.
            // Recovery needs the original buffer, which deserializeDI alone does not have.
            return ScriptHashes.auxiliary(
                    result.getMetadataParseError() == null ? result : DataItemIsolation.auxiliary(bytes), bytes);
        } catch (StackOverflowError e) {
            // Restart at a known boundary; the decoder's partially consumed state is discarded.
            return ScriptHashes.auxiliary(DataItemIsolation.auxiliary(bytes), bytes);
        }
    }

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
                                .filter(nativeScriptDI -> !Special.BREAK.equals(nativeScriptDI))
                                .map(nativeScriptDI -> WitnessesSerializer.INSTANCE.deserializeNativeScript((Array) nativeScriptDI))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                    }

                    //plutus_v1_script
                    if (plutusV1ScriptsValueDI != null) {
                        Array plutusV1ScriptsArray = (Array) plutusV1ScriptsValueDI;
                        plutusV1scripts = plutusV1ScriptsArray.getDataItems()
                                .stream()
                                .filter(script -> script != Special.BREAK)
                                .map(script -> ScriptHashes.plutus(PlutusScriptType.PlutusScriptV1, script, false))
                                .collect(Collectors.toList());
                    }

                    //plutus_v2_script
                    if (plutusV2ScriptsValueDI != null) {
                        Array plutusV2ScriptsArray = (Array) plutusV2ScriptsValueDI;
                        plutusV2scripts = plutusV2ScriptsArray.getDataItems()
                                .stream()
                                .filter(script -> script != Special.BREAK)
                                .map(script -> ScriptHashes.plutus(PlutusScriptType.PlutusScriptV2, script, false))
                                .collect(Collectors.toList());
                    }

                    //plutus_v3_script
                    if (plutusV3ScriptsValueDI != null) {
                        Array plutusV3ScriptsArray = (Array) plutusV3ScriptsValueDI;
                        plutusV3scripts = plutusV3ScriptsArray.getDataItems()
                                .stream()
                                .filter(script -> script != Special.BREAK)
                                .map(script -> ScriptHashes.plutus(PlutusScriptType.PlutusScriptV3, script, false))
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
                        NativeScript script = WitnessesSerializer.INSTANCE.deserializeNativeScript((Array) auxScriptDI);
                        if (script != null) {
                            nativeScripts.add(script);
                        }
                    }
                }
            }

            String metadataCbor = null;
            String metadataJson = null;
            String metadataParseError = null;
            if (metadata != null) {
                byte[] cbor;
                try {
                    // Preserve the existing canonical representation for ordinary metadata.
                    cbor = metadata.serialize();
                } catch (StackOverflowError e) {
                    // The canonical encoder is recursive. Retain valid CBOR using the existing
                    // iterative array encoder, and skip optional JSON for this oversized tree.
                    cbor = CborSerializationUtil.serialize(((CBORMetadata) metadata).getData(), false);
                    metadataParseError = "Metadata JSON conversion skipped: recursive CBOR serialization "
                            + "exceeded the available stack";
                }
                metadataCbor = HexUtil.encodeHexString(cbor);
                if (metadataParseError == null) {
                    try {
                        metadataJson = MetadataToJsonNoSchemaConverter.cborBytesToJson(cbor);
                    } catch (Exception e) {
                        metadataParseError = "Metadata JSON conversion failed: " + e.getClass().getSimpleName();
                    } catch (StackOverflowError e) {
                        metadataParseError = "Metadata JSON conversion exceeded the available stack";
                    }
                }
            }

            return AuxData.builder()
                    .metadataCbor(metadataCbor)
                    .metadataJson(metadataJson)
                    .metadataParseError(metadataParseError)
                    .nativeScripts(nativeScripts)
                    .plutusV1Scripts(plutusV1scripts)
                    .plutusV2Scripts(plutusV2scripts)
                    .plutusV3Scripts(plutusV3scripts)
                    .build();

        } catch (CborDeserializationException e) {
            throw new CborRuntimeException("AuxiliaryData deserialization failed", e);
        }
    }
}
