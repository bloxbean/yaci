package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.model.certs.GenesisKeyDelegation;
import com.bloxbean.cardano.yaci.core.model.certs.StakeRegistration;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** yaci#113: size checks counted the BREAK that the old decoder kept in an indefinite array. */
class IndefiniteArrayTest {
    private static final String VKEY = "5820" + "11".repeat(32);
    private static final String SIGNATURE = "5840" + "22".repeat(64);
    private static final String HASH28 = "581c" + "33".repeat(28);

    @Test
    void witnessesWithIndefiniteArraysAtEveryLevel() {
        Witnesses witness = WitnessesSerializer.INSTANCE.deserialize(
                HexUtil.decodeHexString("a2" + "009f9f" + VKEY + SIGNATURE + "ffff"
                        + "029f9f" + VKEY + SIGNATURE + "5820" + "44".repeat(32) + "41a0" + "ffff"));
        assertThat(witness.getVkeyWitnesses()).hasSize(1);
        assertThat(witness.getVkeyWitnesses().get(0).getSignature()).isEqualTo("22".repeat(64));
        assertThat(witness.getBootstrapWitnesses()).hasSize(1);
        assertThat(witness.getBootstrapWitnesses().get(0).getAttributes()).isEqualTo("a0");
    }

    @Test
    void certificatesWithIndefiniteArrays() {
        var registration = CertificateSerializer.INSTANCE.deserializeDI(item("9f00" + "9f00" + HASH28 + "ffff"));
        assertThat(registration).isInstanceOf(StakeRegistration.class);
        assertThat(((StakeRegistration) registration).getStakeCredential().getHash()).isEqualTo("33".repeat(28));

        var genesis = (GenesisKeyDelegation) CertificateSerializer.INSTANCE.deserializeDI(
                item("9f05" + HASH28 + HASH28 + "5820" + "44".repeat(32) + "ff"));
        assertThat(genesis.getGenesisHash()).isEqualTo("33".repeat(28));
        assertThat(genesis.getVrfKeyHash()).isEqualTo("44".repeat(32));
    }

    private static DataItem item(String hex) {
        return CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
    }
}
