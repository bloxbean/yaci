package com.bloxbean.cardano.yaci.core.util;

import com.bloxbean.cardano.yaci.core.model.Era;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TxCborUtilTest {

    @Test
    void assembleTxCbor_shelleyEnvelope() {
        byte[] txCbor = TxCborUtil.assembleTxCbor(Era.Shelley,
                HexUtil.decodeHexString("a0"),
                HexUtil.decodeHexString("a0"),
                null,
                true);

        assertThat(HexUtil.encodeHexString(txCbor)).isEqualTo("83a0a0f6");
    }

    @Test
    void assembleTxCbor_alonzoEnvelopeWithInvalidAndAuxData() {
        byte[] txCbor = TxCborUtil.assembleTxCbor(Era.Alonzo,
                HexUtil.decodeHexString("a0"),
                HexUtil.decodeHexString("a0"),
                HexUtil.decodeHexString("a10102"),
                false);

        assertThat(HexUtil.encodeHexString(txCbor)).isEqualTo("84a0a0f4a10102");
    }

    @Test
    void assembleTxCbor_returnsNullForMissingRequiredSegments() {
        assertThat(TxCborUtil.assembleTxCbor(Era.Conway, null, HexUtil.decodeHexString("a0"), null, true))
                .isNull();
        assertThat(TxCborUtil.assembleTxCbor(Era.Conway, HexUtil.decodeHexString("a0"), null, null, true))
                .isNull();
    }

    @Test
    void assembleTxCbor_returnsNullForByronAndNullEra() {
        assertThat(TxCborUtil.assembleTxCbor(Era.Byron,
                HexUtil.decodeHexString("a0"), HexUtil.decodeHexString("a0"), null, true))
                .isNull();
        assertThat(TxCborUtil.assembleTxCbor(null,
                HexUtil.decodeHexString("a0"), HexUtil.decodeHexString("a0"), null, true))
                .isNull();
    }
}
