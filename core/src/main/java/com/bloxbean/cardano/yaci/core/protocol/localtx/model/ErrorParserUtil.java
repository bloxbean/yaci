package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared helper methods for all era-specific error parsers.
 */
class ErrorParserUtil {

    static TxSubmissionError unknownError(Era era, String rule, int tag, DataItem di) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName("UnknownError")
                .tag(tag)
                .message("Unknown error (tag " + tag + ")")
                .rawCborHex(serializeToHex(di))
                .build();
    }

    static TxSubmissionError wrapError(Era era, String rule, String errorName, int tag, String message,
                                       List<TxSubmissionError> children) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .children(children != null ? children : Collections.emptyList())
                .build();
    }

    static TxSubmissionError leafError(Era era, String rule, String errorName, int tag, String message) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .build();
    }

    static TxSubmissionError leafError(Era era, String rule, String errorName, int tag, String message,
                                       Map<String, Object> detail) {
        return TxSubmissionError.builder()
                .era(era)
                .rule(rule)
                .errorName(errorName)
                .tag(tag)
                .message(message)
                .detail(detail != null ? detail : Collections.emptyMap())
                .build();
    }

    static String serializeToHex(DataItem di) {
        if (di == null) return null;
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(di));
        } catch (Exception e) {
            return null;
        }
    }

    static int toIntSafe(DataItem di) {
        try {
            return CborSerializationUtil.toInt(di);
        } catch (Exception e) {
            return -1;
        }
    }

    static long toLongSafe(DataItem di) {
        try {
            return CborSerializationUtil.toLong(di);
        } catch (Exception e) {
            return -1;
        }
    }

    static String toStringSafe(DataItem di) {
        if (di == null) return "";
        try {
            if (di instanceof UnicodeString) {
                return ((UnicodeString) di).getString();
            } else if (di instanceof ByteString) {
                return HexUtil.encodeHexString(((ByteString) di).getBytes());
            } else {
                return di.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    static String toHexSafe(DataItem di) {
        if (di == null) return "";
        try {
            if (di instanceof ByteString) {
                return HexUtil.encodeHexString(((ByteString) di).getBytes());
            }
            return serializeToHex(di);
        } catch (Exception e) {
            return "";
        }
    }

    static List<DataItem> getArrayItems(DataItem di) {
        if (di instanceof Array) {
            return ((Array) di).getDataItems();
        }
        return Collections.emptyList();
    }

    static List<String> extractHashList(DataItem di) {
        List<String> hashes = new ArrayList<>();
        if (di instanceof Array) {
            for (DataItem item : ((Array) di).getDataItems()) {
                hashes.add(toHexSafe(item));
            }
        } else if (di instanceof ByteString) {
            hashes.add(toHexSafe(di));
        }
        return hashes;
    }

    static List<String> extractTxInputList(DataItem di) {
        List<String> inputs = new ArrayList<>();
        if (di instanceof Array) {
            for (DataItem item : ((Array) di).getDataItems()) {
                if (item instanceof Array) {
                    List<DataItem> parts = ((Array) item).getDataItems();
                    if (parts.size() >= 2) {
                        inputs.add(toHexSafe(parts.get(0)) + "#" + toIntSafe(parts.get(1)));
                    }
                }
            }
        }
        return inputs;
    }

    static Map<String, Object> detail(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    static Map<String, Object> detail(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    static Map<String, Object> detail(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return map;
    }

    /**
     * Map wire era index (0-6) to model Era enum.
     */
    static Era wireIndexToEra(int index) {
        switch (index) {
            case 0: return Era.Byron;
            case 1: return Era.Shelley;
            case 2: return Era.Allegra;
            case 3: return Era.Mary;
            case 4: return Era.Alonzo;
            case 5: return Era.Babbage;
            case 6: return Era.Conway;
            default: return null;
        }
    }

    static String wireIndexToEraName(int index) {
        Era era = wireIndexToEra(index);
        return era != null ? era.name() : "Unknown(" + index + ")";
    }
}
