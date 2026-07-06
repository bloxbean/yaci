package com.bloxbean.cardano.yaci.core.util;

import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Slf4j
public class TxCborUtil {
    // Envelope bytes fixed by RFC 8949 (CBOR): 0x83/0x84 are definite-length array
    // headers for 3/4 elements (major type 4: 0x80 | element count, single byte for
    // counts up to 23); 0xf5/0xf4/0xf6 are the simple values true/false/null
    // (major type 7). cbor-java exposes no encoded-byte constants for these
    // (SimpleValue.TRUE etc. are DataItem objects), and this utility deliberately
    // splices raw bytes without an encoder, so they are declared here directly.
    private static final byte ARRAY_3 = (byte) 0x83;
    private static final byte ARRAY_4 = (byte) 0x84;
    private static final byte TRUE = (byte) 0xf5;
    private static final byte FALSE = (byte) 0xf4;
    private static final byte NULL = (byte) 0xf6;

    /**
     * Assemble a standalone transaction CBOR envelope from raw block segment bytes.
     * The outer 3/4-element definite array is convention: blocks store transaction
     * bodies, witness sets, validity flags, and auxiliary data as separate segments,
     * so the submitter's original outer transaction framing is not recoverable.
     *
     * @param era transaction era
     * @param body raw transaction body bytes
     * @param witnessSet raw transaction witness-set bytes
     * @param auxData raw auxiliary data bytes, or null when absent
     * @param isValid transaction validity flag for Alonzo and later
     * @return assembled transaction CBOR, or null when required data is missing
     */
    public static byte[] assembleTxCbor(Era era,
                                        byte[] body,
                                        byte[] witnessSet,
                                        byte[] auxData,
                                        boolean isValid) {
        if (era == null || era == Era.Byron) {
            log.debug("Cannot assemble full tx cbor for era: {}", era);
            return null;
        }

        if (isMissing(body) || isMissing(witnessSet)) {
            log.debug("Cannot assemble full tx cbor. Missing body or witness-set bytes");
            return null;
        }

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (era == Era.Shelley || era == Era.Allegra || era == Era.Mary) {
                baos.write(ARRAY_3);
                baos.write(body);
                baos.write(witnessSet);
                baos.write(auxData != null ? auxData : new byte[]{NULL});
            } else if (era.getValue() >= Era.Alonzo.getValue()) {
                baos.write(ARRAY_4);
                baos.write(body);
                baos.write(witnessSet);
                baos.write(isValid ? TRUE : FALSE);
                baos.write(auxData != null ? auxData : new byte[]{NULL});
            } else {
                log.debug("Cannot assemble full tx cbor for unsupported era: {}", era);
                return null;
            }

            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Unable to assemble full tx cbor", e);
            return null;
        }
    }

    private static boolean isMissing(byte[] bytes) {
        return bytes == null || bytes.length == 0;
    }
}
