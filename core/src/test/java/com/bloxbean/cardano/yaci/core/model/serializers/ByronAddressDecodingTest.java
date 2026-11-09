package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.crypto.Base58;
import com.bloxbean.cardano.client.crypto.exception.AddressFormatException;
import com.bloxbean.cardano.yaci.core.model.byron.ByronAddress;
import com.bloxbean.cardano.yaci.core.model.byron.ByronAddressAttr;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.zip.CRC32;

import static com.bloxbean.cardano.yaci.core.model.byron.ByronAddressTypes.PUB_KEY;
import static com.bloxbean.cardano.yaci.core.model.byron.ByronAddressTypes.REDEEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Byron output's address, read through CCL's ByronAddress. The expected models are what the serializer's own
 * decoder gave before, which the CCL decoder matched on every distinct mainnet Byron address.
 */
class ByronAddressDecodingTest {

    @Test
    void aDaedalusAddressKeepsItsDerivationPath() {
        String base58 = "DdzFFzCqrhszg6cqZvDhEwUX7cZyNzdycAVpm4Uo2vjKMgTLrVqiVKi3MBt2tFAtDe7NkptK6TAhVkiYzhavmKV5hE79"
                + "CWwJnPCJTREK";

        assertThat(read(base58)).isEqualTo(new ByronAddress(base58,
                "9fb5418b25eb98db1f8e80bc697bbe0e2a9a2ffa87120d6fdcaa8ce2",
                new ByronAddressAttr(null, "581e581c04d160b3bcb17ef13564efebbc15364c59b7b17c87c86d19b3d0881a"),
                PUB_KEY));
    }

    @Test
    void aRedeemAddressHasNoAttributes() {
        String base58 = "Ae2tdPwUPEZ3MHKkpT5Bpj549vrRH7nBqYjNXnCV8G2Bc2YxNcGHEa8ykDp";

        assertThat(read(base58)).isEqualTo(new ByronAddress(base58,
                "41941d3c8d3a90fe493505094e388fa802cd5d043aadd972066b6444", new ByronAddressAttr(null, null), REDEEM));
    }

    @Test
    void aTestnetAddressKeepsItsProtocolMagic() {
        String base58 = "FHnt4NL7yPXhCzCHVywZLqVsvwuG3HvwmjKXQJBrXh3h2aigv6uxkePbpzRNV8q";

        assertThat(read(base58)).isEqualTo(new ByronAddress(base58,
                "056d8907b4530dabec0ab77456a2b5c7e695150d7534380a8093091e", new ByronAddressAttr("4101", null),
                PUB_KEY));
    }

    @Test
    void anAddressTheLedgerWouldNotDecodeIsAParseError() {
        // type 1, which the Byron ledger does not decode; the old reader called it "Script"
        String payload = "83" + "581c" + "11".repeat(28) + "a0" + "01";

        assertThatThrownBy(() -> ByronBlockSerializer.deserializeAddress(address(payload)))
                .isInstanceOf(AddressFormatException.class);
    }

    private static ByronAddress read(String base58) {
        Array address = (Array) CborSerializationUtil.deserializeOne(Base58.decode(base58));
        return ByronBlockSerializer.deserializeAddress(address);
    }

    private static Array address(String payloadHex) {
        byte[] payload = HexUtil.decodeHexString(payloadHex);
        CRC32 crc = new CRC32();
        crc.update(payload);
        String hex = "82d818" + "58" + String.format("%02x", payload.length) + payloadHex
                + "1a" + String.format("%08x", crc.getValue());
        return (Array) CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
    }
}
