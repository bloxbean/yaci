package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.transaction.raw.RawScript;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.bloxbean.cardano.yaci.core.model.NativeScriptType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sets each parsed native script's hash from the script as received (yaci#191): {@code blake2b224(0x00 || script)},
 * with the walk over the source bytes iterative at any depth. The hash is optional data: a failure here leaves it
 * null, logged, and never fails the block or changes which scripts are accepted.
 */
@Slf4j
final class NativeScriptHashes {
    private static final int NATIVE_SCRIPT_LANGUAGE = 0;
    private static final int MAJOR_ARRAY = 4;
    private static final BigInteger NATIVE_SCRIPTS_KEY = BigInteger.ONE; // In witnesses and tag-259 auxiliary data.
    private static final int SHELLEY_MA_NATIVE_SCRIPTS = 1; // [metadata, native scripts]
    private static final int BLOCK_BODY = 1;
    private static final int BLOCK_AUXILIARY_DATA = 3;

    private NativeScriptHashes() {
    }

    static void fromWitness(List<NativeScript> parsed, byte[] witness) {
        if (parsed == null || parsed.isEmpty()) return;
        try {
            set(parsed, field(untagged(CborSpan.of(witness))));
        } catch (RuntimeException e) {
            log.warn("Native script hashes are unavailable for a witness set", e);
        }
    }

    static void fromAuxiliaryData(List<NativeScript> parsed, byte[] auxiliaryData) {
        if (parsed == null || parsed.isEmpty()) return;
        try {
            set(parsed, scriptsOf(CborSpan.of(auxiliaryData)));
        } catch (RuntimeException e) {
            log.warn("Native script hashes are unavailable for auxiliary data", e);
        }
    }

    /** The block's auxiliary data is walked only when one of its parsed entries holds a native script. */
    static void fromBlock(Map<Integer, AuxData> parsed, byte[] block) {
        if (parsed.values().stream().allMatch(aux -> aux.getNativeScripts() == null
                || aux.getNativeScripts().isEmpty())) {
            return;
        }
        try {
            CborSpan auxiliaryData = CborSpan.at(block, 0).get(BLOCK_BODY).get(BLOCK_AUXILIARY_DATA);
            for (Map.Entry<CborSpan, CborSpan> entry : auxiliaryData.entries()) {
                AuxData aux = parsed.get(entry.getKey().asBigInteger().intValue());
                if (aux == null || aux.getNativeScripts() == null || aux.getNativeScripts().isEmpty()) continue;
                set(aux.getNativeScripts(), scriptsOf(entry.getValue()));
            }
        } catch (RuntimeException e) {
            log.warn("Native script hashes are unavailable for a block's auxiliary data", e);
        }
    }

    private static CborSpan scriptsOf(CborSpan auxiliaryData) {
        CborSpan content = untagged(auxiliaryData);
        return content.majorType() == MAJOR_ARRAY ? content.get(SHELLEY_MA_NATIVE_SCRIPTS) : field(content);
    }

    // The last value of a repeated key, as the parsed map keeps.
    private static CborSpan field(CborSpan map) {
        CborSpan value = null;
        for (Map.Entry<CborSpan, CborSpan> entry : map.entries()) {
            CborSpan key = entry.getKey();
            if (key.tag() == -1 && key.majorType() == 0 && NATIVE_SCRIPTS_KEY.equals(key.asBigInteger())) {
                value = entry.getValue();
            }
        }
        if (value == null) throw new IllegalArgumentException("No native script field");
        return value;
    }

    private static void set(List<NativeScript> parsed, CborSpan scripts) {
        // The parsed list leaves out unknown script types (NativeScriptJson), so they are left out here too.
        List<CborSpan> known = new ArrayList<>(parsed.size());
        for (CborSpan script : untagged(scripts).items()) {
            if (untagged(script).get(0).asLong() <= NativeScriptType.REQUIRE_TIME_BEFORE) known.add(script);
        }
        if (known.size() != parsed.size()) {
            log.warn("Native script count mismatch. parsed: {}, raw: {}", parsed.size(), known.size());
            return;
        }
        for (int i = 0; i < known.size(); i++) {
            NativeScript script = parsed.get(i);
            String hash = HexUtil.encodeHexString(new RawScript(NATIVE_SCRIPT_LANGUAGE, known.get(i)).hash());
            parsed.set(i, new NativeScript(script.getType(), script.getContent(), script.getParseError(), hash));
        }
    }

    private static CborSpan untagged(CborSpan span) {
        CborSpan content = span;
        while (content.tag() != -1) content = content.untag();
        return content;
    }
}
