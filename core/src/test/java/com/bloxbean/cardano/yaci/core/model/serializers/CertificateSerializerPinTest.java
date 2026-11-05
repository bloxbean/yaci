package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.certs.GenesisKeyDelegation;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins ADR 0003 section 12, for M1: a genesis delegation that fails to parse prints a stack trace and gives an empty
 * certificate instead of failing. A separate decision should remove this; until then a migration must not change it.
 */
class CertificateSerializerPinTest {
    private static final String HASH28 = "581c" + "11".repeat(28);
    private static final String HASH32 = "5820" + "22".repeat(32);

    @Test
    void genesisDelegationThatFailsToParseIsAnEmptyCertificateAndAStackTrace() {
        // Too few items, and the right count with integers in place of hashes.
        for (String cert : new String[]{"8205" + "00", "83" + "05" + HASH28 + HASH28, "84" + "05" + "010203"}) {
            PrintStream originalErr = System.err;
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            System.setErr(new PrintStream(err, true));
            GenesisKeyDelegation parsed;
            try {
                parsed = (GenesisKeyDelegation) CertificateSerializer.INSTANCE.deserializeDI(
                        CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(cert)));
            } finally {
                System.setErr(originalErr);
            }
            assertThat(parsed.getGenesisHash()).as(cert).isNull();
            assertThat(parsed.getGenesisDelegateHash()).as(cert).isNull();
            assertThat(parsed.getVrfKeyHash()).as(cert).isNull();
            assertThat(err.toString()).as(cert).contains("Exception").contains("GenesisKeyDelegationSerializer");
        }
    }

    @Test
    void wellFormedGenesisDelegationIsRead() {
        GenesisKeyDelegation parsed = (GenesisKeyDelegation) CertificateSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(
                        HexUtil.decodeHexString("8405" + HASH28 + HASH28 + HASH32)));
        assertThat(parsed.getGenesisHash()).isEqualTo("11".repeat(28));
        assertThat(parsed.getGenesisDelegateHash()).isEqualTo("11".repeat(28));
        assertThat(parsed.getVrfKeyHash()).isEqualTo("22".repeat(32));
    }
}
