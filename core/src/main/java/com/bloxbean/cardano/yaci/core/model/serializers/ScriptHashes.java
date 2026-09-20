package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.serializers.util.CborSlice;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hash scripts without rebuilding a native-script tree or parsing its JSON.
 */
@Slf4j
final class ScriptHashes {
    private static final Map<PlutusScriptType, Integer> PLUTUS_PREFIXES = Map.of(
            PlutusScriptType.PlutusScriptV1, 1,
            PlutusScriptType.PlutusScriptV2, 2,
            PlutusScriptType.PlutusScriptV3, 3);

    private ScriptHashes() {
    }

    static PlutusScript plutus(PlutusScriptType type, DataItem script, boolean cborWrapped) {
        // Preserve the historical content format: wrapped in witnesses, raw in auxiliary data.
        String content = cborWrapped
                ? HexUtil.encodeHexString(CborSerializationUtil.serialize(script, false))
                : CborSerializationUtil.toHex(script);
        return new PlutusScript(type, content,
                plutusHash(type, script));
    }

    /** Optional hashing must never turn a supported script's decoded content into a block parse failure. */
    private static String plutusHash(PlutusScriptType type, DataItem script) {
        try {
            Integer prefix = type == null ? null : PLUTUS_PREFIXES.get(type);
            if (prefix == null) throw new IllegalArgumentException("Unsupported Plutus version: " + type);
            return hash(prefix, ((ByteString) script).getBytes());
        } catch (Exception e) {
            log.error("Plutus script hash unavailable for version {}", type, e);
            return null;
        }
    }

    /**
     * Hash the embedded [language, body] without constructing a script object or JSON.
     * Checks CBOR framing and body kind, not native-script semantics or Plutus program validity.
     * An unrecognized/malformed reference leaves the original scriptRef available to callers.
     */
    static String reference(byte[] bytes) {
        try {
            CborSlice root = CborSlice.of(bytes);
            if (root.tag() != -1) return null;
            List<CborSlice> fields = root.items(MajorType.ARRAY);
            if (fields.size() != 2 || fields.get(0).tag() != -1
                    || fields.get(0).type() != MajorType.UNSIGNED_INTEGER) return null;
            int language = DataItemIsolation.unsigned(fields.get(0));
            CborSlice body = fields.get(1);
            if (body.tag() != -1) return null;
            if (language == 0 && body.type() == MajorType.ARRAY) {
                return hash(0, body.bytes());
            }
            if (language >= 1 && language <= 3 && body.type() == MajorType.BYTE_STRING) {
                ByteString payload = (ByteString) CborSerializationUtil.deserializeOne(body.bytes());
                return hash(language, payload.getBytes());
            }
            return null;
        } catch (Exception | StackOverflowError e) {
            // Optional enrichment must not prevent output delivery for malformed embedded CBOR.
            return null;
        }
    }

    private static String hash(int prefix, byte[] bytes) {
        byte[] preimage = new byte[bytes.length + 1];
        preimage[0] = (byte) prefix;
        System.arraycopy(bytes, 0, preimage, 1, bytes.length);
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(preimage));
    }

    static Witnesses witness(Witnesses witness, byte[] bytes) {
        tryNativeScripts(witness.getNativeScripts(), () -> field(CborSlice.first(bytes), 1));
        return witness;
    }

    static AuxData auxiliary(AuxData auxiliary, byte[] bytes) {
        tryNativeScripts(auxiliary.getNativeScripts(), () -> {
            CborSlice root = CborSlice.first(bytes);
            return root.type() == MajorType.ARRAY
                    ? root.items(MajorType.ARRAY).get(1) : field(root, 1);
        });
        return auxiliary;
    }

    /** Reuse field 1 when the caller already extracted witness fields for datums/redeemers. */
    static void witnessNativeScripts(Witnesses witness, byte[] scripts) {
        tryNativeScripts(witness.getNativeScripts(), () -> scripts == null ? null : CborSlice.of(scripts));
    }

    /** Isolate optional extraction/alignment errors for standalone, block and recovery callers alike. */
    private static void tryNativeScripts(List<NativeScript> scripts, NativeSource source) {
        if (scripts == null || scripts.isEmpty()) return;
        try {
            nativeScripts(scripts, source.get());
        } catch (Exception | StackOverflowError e) {
            log.error("Native script hashes unavailable: original-byte extraction or alignment failed", e);
        }
    }

    @FunctionalInterface
    private interface NativeSource {
        CborSlice get() throws Exception;
    }

    @SneakyThrows
    private static CborSlice field(CborSlice map, int key) {
        CborSlice result = null;
        List<CborSlice> fields = map.items(MajorType.MAP);
        for (int i = 0; i < fields.size(); i += 2) {
            if (DataItemIsolation.unsigned(fields.get(i)) == key) result = fields.get(i + 1);
        }
        return result;
    }

    @SneakyThrows
    private static void nativeScripts(List<NativeScript> parsed, CborSlice source) {
        if (source == null) throw new IllegalArgumentException("Missing original native scripts");
        List<byte[]> originals = new ArrayList<>();
        // Validate the whole correspondence before assigning anything: a later mismatch must not
        // leave earlier scripts with hashes attached to potentially misaligned source values.
        for (CborSlice script : source.items(MajorType.ARRAY)) {
            byte[] raw = script.bytes();
            int type = DataItemIsolation.unsigned(CborSlice.arrayItem(raw, 0));
            if (type > NativeScriptType.REQUIRE_TIME_BEFORE) continue;
            int index = originals.size();
            if (index >= parsed.size()) throw new IllegalArgumentException("Native script count mismatch");
            if (parsed.get(index).getType() != type) {
                throw new IllegalArgumentException("Native script type mismatch");
            }
            originals.add(raw);
        }
        if (originals.size() != parsed.size()) throw new IllegalArgumentException("Native script count mismatch");
        for (int index = 0; index < originals.size(); index++) {
            NativeScript value = parsed.get(index);
            String scriptHash = null;
            try {
                scriptHash = hash(0, originals.get(index));
            } catch (Exception e) {
                log.error("Native script hash unavailable at index {}", index, e);
            }
            parsed.set(index, value.toBuilder().hash(scriptHash).build());
        }
    }
}
