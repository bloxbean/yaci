package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredential;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A credential's type is read whole: 2^32 and 2^32 + 1 are not 0 and 1. */
class CredentialTypeTest {
    private static final String HASH = "581c" + "00".repeat(28);

    @Test
    void typesZeroAndOneAreKeyAndScriptHashes() {
        assertThat(Credential.deserialize(array("8200" + HASH)).getType()).isEqualTo(StakeCredType.ADDR_KEYHASH);
        assertThat(Credential.deserialize(array("8201" + HASH)).getType()).isEqualTo(StakeCredType.SCRIPTHASH);
        assertThat(StakeCredential.deserialize(array("8200" + HASH)).getType()).isEqualTo(StakeCredType.ADDR_KEYHASH);
        assertThat(StakeCredential.deserialize(array("8201" + HASH)).getType()).isEqualTo(StakeCredType.SCRIPTHASH);
    }

    @Test
    void typesThatWrapToZeroOrOneAreRejected() {
        for (String type : new String[]{"1b0000000100000000", "1b0000000100000001"}) {
            assertThatThrownBy(() -> Credential.deserialize(array("82" + type + HASH)))
                    .isInstanceOf(CborRuntimeException.class);
            assertThatThrownBy(() -> StakeCredential.deserialize(array("82" + type + HASH)))
                    .isInstanceOf(CborRuntimeException.class);
        }
    }

    private static Array array(String hex) {
        return (Array) CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
    }
}
