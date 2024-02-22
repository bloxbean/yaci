package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.plutus.spec.PlutusV1Script;
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script;
import com.bloxbean.cardano.client.spec.Script;
import com.bloxbean.cardano.client.transaction.spec.script.*;
import com.bloxbean.cardano.client.util.JsonUtil;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.SneakyThrows;

import java.util.ArrayList;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

//TODO -- More testing required for deserialization --> serialization
public enum WitnessesSerializer implements Serializer<Witnesses> {
    INSTANCE;

    @Override
    @SneakyThrows
    public Witnesses deserializeDI(DataItem di) {
        Map witnessMap = (Map) di;
        DataItem vkWitnessesArray = witnessMap.get(new UnsignedInteger(0));
        DataItem nativeScriptArray = witnessMap.get(new UnsignedInteger(1));
        DataItem bootstrapWitnessArray = witnessMap.get(new UnsignedInteger(2));
        DataItem plutusScriptArray = witnessMap.get(new UnsignedInteger(3));
        DataItem plutusDataArray = witnessMap.get(new UnsignedInteger(4));
        DataItem redeemerArray = witnessMap.get(new UnsignedInteger(5));
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
                    PlutusV1Script plutusV1Script = PlutusV1Script.deserialize((ByteString) plutusV1ScriptDI);

                    if (plutusV1Script != null) {
                        PlutusScript plutusScript = new PlutusScript(String.valueOf(plutusV1Script.getScriptType()), plutusV1Script.getCborHex());
                        plutusV1Scripts.add(plutusScript);
                    }
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
        if (redeemerArray != null) {
            List<DataItem> redeemerDIList = ((Array) redeemerArray).getDataItems();
            for (DataItem redeemerDI : redeemerDIList) {
                if (redeemerDI == Special.BREAK) continue;
                //Redeemer redeemer = new Redeemer(HexUtil.encodeHexString(CborSerializationUtil.serialize(redeemerDI, false)));
                Redeemer redeemer = Redeemer.deserialize((Array) redeemerDI);
                redeemerList.add(redeemer);
               // redeemers.add(Redeemer.deserialize((Array) redeemerDI)); //TODO -- convert redeemer to json
            }
        }

        //plutus_v2_script (Babbage era or Post Alonzo)
        List<PlutusScript> plutusV2Scripts = new ArrayList<>();
        if (plutusV2ScriptArray != null) {
            List<DataItem> plutusV2ScriptDIList = ((Array) plutusV2ScriptArray).getDataItems();
            try {
                for (DataItem plutusV2ScriptDI : plutusV2ScriptDIList) {
                    if (plutusV2ScriptDI == Special.BREAK) continue;
                    PlutusV2Script plutusV2Script = PlutusV2Script.deserialize((ByteString) plutusV2ScriptDI);

                    if (plutusV2Script != null) {
                        PlutusScript plutusScript = new PlutusScript(String.valueOf(plutusV2Script.getScriptType()), plutusV2Script.getCborHex());
                        plutusV2Scripts.add(plutusScript);
                    }
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
                    String scriptCborHex = toHex(plutusV3ScriptDI);

                    PlutusScript plutusScript = new PlutusScript(String.valueOf(3), scriptCborHex);
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

        String pubKey = HexUtil.encodeHexString(((ByteString) vkeyDI).getBytes());
        String signature = HexUtil.encodeHexString(((ByteString) sigDI).getBytes());
        String chainCode = HexUtil.encodeHexString(((ByteString) chainCodeDI).getBytes());
        String attributes = HexUtil.encodeHexString(((ByteString) attributesDI).getBytes());

        return new BootstrapWitness(pubKey, signature, chainCode, attributes);
    }

    private VkeyWitness deserializeVkWitness(Array vkWitness) {
        List<DataItem> dataItemList = vkWitness.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2)
            throw new CborRuntimeException("VkeyWitness deserialization error. Invalid no of DataItem");

        DataItem vkeyDI = dataItemList.get(0);
        DataItem sigDI = dataItemList.get(1);

        String key = HexUtil.encodeHexString(((ByteString) vkeyDI).getBytes());
        String signature = HexUtil.encodeHexString(((ByteString) sigDI).getBytes());

        return new VkeyWitness(key, signature);
    }

    public NativeScript deserializeNativeScript(Array nativeScriptArray) {
        List<DataItem> dataItemList = nativeScriptArray.getDataItems();
        if (dataItemList == null || dataItemList.size() == 0) {
            throw new CborRuntimeException("NativeScript deserialization failed. Invalid no of DataItem");
        }

        int type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue();
        Script script = null;
        try {
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
        } catch (Exception e) {
            throw new CborRuntimeException("Error parsing native script");
        }

        return new NativeScript(type, JsonUtil.getPrettyJson(script));
    }
}
