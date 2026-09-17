package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.*;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.HexUtil.encodeHexString;

//TODO -- More testing required for deserialization --> serialization
@Slf4j
public enum WitnessesSerializer implements Serializer<Witnesses> {
    INSTANCE;

    // Each nested all/any/atLeast script adds two CBOR arrays. Bound recursive client-library
    // parsing and Jackson serialization independently of the thread's available stack size.
    private static final int MAX_NATIVE_SCRIPT_ARRAY_DEPTH = 256;
    private static final ObjectWriter NATIVE_SCRIPT_JSON = new ObjectMapper().writerWithDefaultPrettyPrinter();

    @Override
    @SneakyThrows
    public Witnesses deserializeDI(DataItem di) {
        Map witnessMap = (Map) di;
        DataItem vkWitnessesArray = witnessMap.get(new UnsignedInteger(0));
        DataItem nativeScriptArray = witnessMap.get(new UnsignedInteger(1));
        DataItem bootstrapWitnessArray = witnessMap.get(new UnsignedInteger(2));
        DataItem plutusScriptArray = witnessMap.get(new UnsignedInteger(3));
        DataItem plutusDataArray = witnessMap.get(new UnsignedInteger(4));
        DataItem redeemersDI = witnessMap.get(new UnsignedInteger(5));
        DataItem plutusV2ScriptArray = witnessMap.get(new UnsignedInteger(6));
        DataItem plutusV3ScriptArray = witnessMap.get(new UnsignedInteger(7));

        //vk witnesses
        List<VkeyWitness> vkeyWitnessList = new ArrayList<>();
        if (vkWitnessesArray != null) { //vkwitnesses
            List<DataItem> vkeyWitnessesDIList = ((Array) vkWitnessesArray).getDataItems();
            for (DataItem vkWitness : vkeyWitnessesDIList) {
                if (vkWitness == SimpleValue.BREAK)
                    continue;
                VkeyWitness vkeyWitness = deserializeVkWitness((Array) vkWitness);
                vkeyWitnessList.add(vkeyWitness);
            }
        }

        //native scripts
        List<NativeScript> nativeScripts = new ArrayList<>();
        if (nativeScriptArray != null) { //nativeScriptArray
            List<DataItem> nativeScriptsDIList = ((Array) nativeScriptArray).getDataItems();
            for (DataItem nativeScriptDI : nativeScriptsDIList) {
                if (nativeScriptDI == Special.BREAK)
                    continue;
                NativeScript nativeScript = deserializeNativeScript((Array) nativeScriptDI);
                if (nativeScript != null)
                    nativeScripts.add(nativeScript);
            }
        }

        //Bootstrap Witnesses
        List<BootstrapWitness> bootstrapWitnesses = new ArrayList<>();
        if (bootstrapWitnessArray != null) {
            List<DataItem> bootstrapWitnessDIList = ((Array) bootstrapWitnessArray).getDataItems();
            for (DataItem bootstrapWitnessDI : bootstrapWitnessDIList) {
                if (bootstrapWitnessDI == SimpleValue.BREAK)
                    continue;
                BootstrapWitness bootstrapWitness = deserializeBootstrapWitness((Array) bootstrapWitnessDI);
                if (bootstrapWitness != null)
                    bootstrapWitnesses.add(bootstrapWitness);
            }
        }

        //plutus_v1_script
        List<PlutusScript> plutusV1Scripts = new ArrayList<>();
        if (plutusScriptArray != null) {
            List<DataItem> plutusV1ScriptDIList = ((Array) plutusScriptArray).getDataItems();

            try {
                for (DataItem plutusV1ScriptDI : plutusV1ScriptDIList) {
                    if (plutusV1ScriptDI == Special.BREAK)
                        continue;

                    String scriptCborHex = encodeHexString(CborSerializationUtil.serialize(plutusV1ScriptDI, false));

                    PlutusScript plutusScript = new PlutusScript(PlutusScriptType.PlutusScriptV1, scriptCborHex);
                    plutusV1Scripts.add(plutusScript);
                }
            } catch (Exception e) {
                throw new CborRuntimeException("PlutusV1 script deserialization failed", e);
            }
        }

        //plutus_data
        List<Datum> datumList = new ArrayList<>();
        if (plutusDataArray != null) {
            List<DataItem> plutusDataDIList = ((Array) plutusDataArray).getDataItems();

            for (DataItem plutusDataDI : plutusDataDIList) {
                if (plutusDataDI == Special.BREAK)
                    continue;
               // Datum datum = new Datum(HexUtil.encodeHexString(CborSerializationUtil.serialize(plutusDataDI, false)), ""); //TODO -- convert to json later'
                Datum datum = Datum.from(plutusDataDI);
                datumList.add(datum);
//                plutusDataList.add(PlutusData.deserialize(plutusDataDI));
            }
        }

        //redeemers
        List<Redeemer> redeemerList = new ArrayList<>();
        if (redeemersDI != null) {
            if (redeemersDI instanceof Array) {
                List<DataItem> redeemerDIList = ((Array) redeemersDI).getDataItems();
                for (DataItem redeemerDI : redeemerDIList) {
                    if (redeemerDI == Special.BREAK) continue;
                    Redeemer redeemer = Redeemer.deserializePreConway((Array) redeemerDI);
                    redeemerList.add(redeemer);
                }
            } else if (redeemersDI instanceof Map) { //conway era
                var redeemersMap = (Map) redeemersDI;
                for (DataItem key : redeemersMap.getKeys()) {
                    var valueDI = redeemersMap.get(key);
                    Redeemer redeemer = Redeemer.deserialize((Array)key, (Array) valueDI);
                    redeemerList.add(redeemer);
                }
            }
        }

        //plutus_v2_script (Babbage era or Post Alonzo)
        List<PlutusScript> plutusV2Scripts = new ArrayList<>();
        if (plutusV2ScriptArray != null) {
            List<DataItem> plutusV2ScriptDIList = ((Array) plutusV2ScriptArray).getDataItems();
            try {
                for (DataItem plutusV2ScriptDI : plutusV2ScriptDIList) {
                    if (plutusV2ScriptDI == Special.BREAK) continue;

                    String scriptCborHex = encodeHexString(CborSerializationUtil.serialize(plutusV2ScriptDI, false));

                    PlutusScript plutusScript = new PlutusScript(PlutusScriptType.PlutusScriptV2, scriptCborHex);
                    plutusV2Scripts.add(plutusScript);

                }
            } catch (Exception e) {
                throw new CborRuntimeException("Plutus V2 script deserialization failed", e);
            }
        }

        //plutus_v3 script (Conway era)
        List<PlutusScript> plutusV3Scripts = new ArrayList<>();
        if (plutusV3ScriptArray != null) {
            List<DataItem> plutusV3ScriptDIList = ((Array) plutusV3ScriptArray).getDataItems();
            try {
                for (DataItem plutusV3ScriptDI : plutusV3ScriptDIList) {
                    if (plutusV3ScriptDI == Special.BREAK) continue;

                    String scriptCborHex = encodeHexString(CborSerializationUtil.serialize(plutusV3ScriptDI, false));

                    PlutusScript plutusScript = new PlutusScript(PlutusScriptType.PlutusScriptV3, scriptCborHex);
                    plutusV3Scripts.add(plutusScript);
                }
            } catch (Exception e) {
                throw new CborRuntimeException("Plutus V3 script deserialization failed", e);
            }
        }

        return new Witnesses(vkeyWitnessList, nativeScripts, bootstrapWitnesses, plutusV1Scripts, datumList, redeemerList, plutusV2Scripts, plutusV3Scripts);
    }

    private BootstrapWitness deserializeBootstrapWitness(Array bootstrapWitnessDI) {
        List<DataItem> dataItemList = bootstrapWitnessDI.getDataItems();
        if (dataItemList == null || dataItemList.size() != 4)
            throw new CborRuntimeException("BootstrapWitness deserialization error. Invalid no of DataItem");

        DataItem vkeyDI = dataItemList.get(0);
        DataItem sigDI = dataItemList.get(1);
        DataItem chainCodeDI = dataItemList.get(2);
        DataItem attributesDI = dataItemList.get(3);

        String pubKey = encodeHexString(((ByteString) vkeyDI).getBytes());
        String signature = encodeHexString(((ByteString) sigDI).getBytes());
        String chainCode = encodeHexString(((ByteString) chainCodeDI).getBytes());
        String attributes = encodeHexString(((ByteString) attributesDI).getBytes());

        return new BootstrapWitness(pubKey, signature, chainCode, attributes);
    }

    private VkeyWitness deserializeVkWitness(Array vkWitness) {
        List<DataItem> dataItemList = vkWitness.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2)
            throw new CborRuntimeException("VkeyWitness deserialization error. Invalid no of DataItem");

        DataItem vkeyDI = dataItemList.get(0);
        DataItem sigDI = dataItemList.get(1);

        String key = encodeHexString(((ByteString) vkeyDI).getBytes());
        String signature = encodeHexString(((ByteString) sigDI).getBytes());

        return new VkeyWitness(key, signature);
    }

    public NativeScript deserializeNativeScript(Array nativeScriptArray) {
        int type = -1;
        try {
            List<DataItem> dataItemList = nativeScriptArray.getDataItems();
            type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValueExact();
            if (exceedsNativeScriptDepth(nativeScriptArray)) {
                return nativeScriptFailure(type, "Native script array nesting exceeds " + MAX_NATIVE_SCRIPT_ARRAY_DEPTH);
            }
            Script script;
            if (type == 0) {
                script = ScriptPubkey.deserialize(nativeScriptArray);
            } else if (type == 1) {
                script = ScriptAll.deserialize(nativeScriptArray);
            } else if (type == 2) {
                script = ScriptAny.deserialize(nativeScriptArray);
            } else if (type == 3) {
                script = ScriptAtLeast.deserialize(nativeScriptArray);
            } else if (type == 4) {
                script = RequireTimeAfter.deserialize(nativeScriptArray);
            } else if (type == 5) {
                script = RequireTimeBefore.deserialize(nativeScriptArray);
            } else {
                return null;
            }
            // Do not use JsonUtil: its error fallback invokes the recursive Script.toString().
            return new NativeScript(type, NATIVE_SCRIPT_JSON.writeValueAsString(script));
        } catch (Exception e) {
            return nativeScriptFailure(type, "Native script parsing failed: " + e.getClass().getSimpleName());
        } catch (StackOverflowError e) {
            // Narrow fallback for recursive third-party code; other VM errors must propagate.
            return nativeScriptFailure(type, "Native script parsing exceeded the available stack");
        }
    }

    private NativeScript nativeScriptFailure(int type, String reason) {
        // Never log the script, DataItem, or exception: formatting them can recurse too.
        log.warn("Unable to parse native script of type {}: {}", type, reason);
        return new NativeScript(type, null, reason);
    }

    private boolean exceedsNativeScriptDepth(Array root) {
        Deque<Iterator<DataItem>> stack = new ArrayDeque<>();
        stack.push(root.getDataItems().iterator());
        while (!stack.isEmpty()) {
            Iterator<DataItem> current = stack.peek();
            if (!current.hasNext()) {
                stack.pop();
                continue;
            }
            DataItem item = current.next();
            if (item instanceof Array) {
                if (stack.size() >= MAX_NATIVE_SCRIPT_ARRAY_DEPTH) {
                    return true;
                }
                stack.push(((Array) item).getDataItems().iterator());
            }
        }
        return false;
    }
}
