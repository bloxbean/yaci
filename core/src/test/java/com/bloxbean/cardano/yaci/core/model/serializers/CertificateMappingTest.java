package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.RegCertSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Certificates through {@link CertificateSerializer}, a mapper over CCL's reader (ADR 0003 D8): the models it fills,
 * what it keeps accepting, and where it differs from the decoder it replaced (I5).
 */
class CertificateMappingTest {
    private static final String KEY = "11".repeat(28);
    private static final String SCRIPT = "22".repeat(28);
    private static final String HASH28 = "581c" + KEY;
    private static final String HASH32 = "5820" + "33".repeat(32);
    private static final String CRED = "8200" + HASH28;
    private static final String SCRIPT_CRED = "8201" + "581c" + SCRIPT;
    private static final String ANCHOR = "82" + "6161" + HASH32;
    private static final String POOL = "8a03" + HASH28 + HASH32 + "01" + "02" + "d81e820103" + "581de1" + KEY + "81"
            + HASH28 + "%s" + "%s";
    private static final String ONE_RELAY = "81" + "8202" + "6161";

    private static DataItem item(String hex) {
        return CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(hex));
    }

    private static Certificate certificate(String hex) {
        return CertificateSerializer.INSTANCE.deserializeDI(item(hex));
    }

    private static PoolRegistration pool(String relays, String metadata) {
        return (PoolRegistration) certificate(String.format(POOL, relays, metadata));
    }

    private static StakeCredential stakeKey() {
        return StakeCredential.fromKeyHash(HexUtil.decodeHexString(KEY));
    }

    private static TransactionBody body(String hex) throws Exception {
        byte[] bytes = HexUtil.decodeHexString(hex);
        DataItem tree = CborSerializationUtil.deserializeOne(bytes);
        return TransactionBodySerializer.INSTANCE.deserializeDI(tree, bytes);
    }

    @Test
    void conwayCertificatesFillTheirModels() {
        assertThat(certificate("8307" + CRED + "05")).isEqualTo(new RegCert(stakeKey(), BigInteger.valueOf(5)));
        assertThat(certificate("8308" + CRED + "05")).isEqualTo(new UnregCert(stakeKey(), BigInteger.valueOf(5)));
        assertThat(certificate("8309" + CRED + "8102")).isEqualTo(new VoteDelegCert(stakeKey(), Drep.abstain()));
        assertThat(certificate("840a" + CRED + HASH28 + "8103"))
                .isEqualTo(new StakeVoteDelegCert(stakeKey(), KEY, Drep.noConfidence()));
        assertThat(certificate("840b" + CRED + HASH28 + "05"))
                .isEqualTo(new StakeRegDelegCert(stakeKey(), KEY, BigInteger.valueOf(5)));
        assertThat(certificate("840c" + CRED + "8200" + HASH28 + "05"))
                .isEqualTo(new VoteRegDelegCert(stakeKey(), Drep.addrKeyHash(KEY), BigInteger.valueOf(5)));
        assertThat(certificate("850d" + CRED + HASH28 + "8201" + HASH28 + "05"))
                .isEqualTo(new StakeVoteRegDelegCert(stakeKey(), KEY, Drep.scriptHash(KEY), BigInteger.valueOf(5)));

        AuthCommitteeHotCert auth = (AuthCommitteeHotCert) certificate("830e" + CRED + SCRIPT_CRED);
        assertThat(auth.getCommitteeColdCredential().getHash()).isEqualTo(KEY);
        assertThat(auth.getCommitteeHotCredential().getType()).isEqualTo(StakeCredType.SCRIPTHASH);
        assertThat(auth.getCommitteeHotCredential().getHash()).isEqualTo(SCRIPT);

        assertThat(((ResignCommitteeColdCert) certificate("830f" + CRED + "f6")).getAnchor()).isNull();
        Anchor anchor = ((RegDrepCert) certificate("8410" + CRED + "05" + ANCHOR)).getAnchor();
        assertThat(anchor.getAnchor_url()).isEqualTo("a");
        assertThat(anchor.getAnchor_data_hash()).isEqualTo("33".repeat(32));
        assertThat(((UnregDrepCert) certificate("8311" + CRED + "05")).getCoin()).isEqualTo(5);
        assertThat(((UpdateDrepCert) certificate("8312" + CRED + ANCHOR)).getAnchor()).isEqualTo(anchor);
    }

    @Test
    void shelleyCertificatesFillTheirModels() {
        assertThat(certificate("8200" + CRED)).isEqualTo(new StakeRegistration(stakeKey()));
        assertThat(certificate("8201" + CRED)).isEqualTo(new StakeDeregistration(stakeKey()));
        assertThat(certificate("8302" + CRED + HASH28))
                .isEqualTo(new StakeDelegation(stakeKey(), new StakePoolId(HexUtil.decodeHexString(KEY))));
        assertThat(certificate("8304" + HASH28 + "05")).isEqualTo(new PoolRetirement(KEY, 5));

        var pool = pool(ONE_RELAY, "82" + "6161" + HASH32).getPoolParams();
        assertThat(pool.getOperator()).isEqualTo(KEY);
        assertThat(pool.getVrfKeyHash()).isEqualTo("33".repeat(32));
        assertThat(pool.getPledge()).isEqualTo(1);
        assertThat(pool.getCost()).isEqualTo(2);
        assertThat(pool.getMargin().getNumerator()).isEqualTo(1);
        assertThat(pool.getMargin().getDenominator()).isEqualTo(3);
        assertThat(pool.getRewardAccount()).isEqualTo("e1" + KEY);
        assertThat(pool.getPoolOwners()).containsExactly(KEY);
        assertThat(pool.getPoolMetadataUrl()).isEqualTo("a");
        assertThat(pool.getPoolMetadataHash()).isEqualTo("33".repeat(32));
    }

    @Test
    void relaysKeepTheirModelsOddities() {
        String relays = "84" + "8400" + "1901bb" + "44c0a80001" + "f6"
                + "8400f6" + "4101" + "f6" // an address of a wrong length reads as null
                + "8301f66161"
                + "82026161";
        assertThat(pool(relays, "f6").getPoolParams().getRelays()).containsExactly(
                new Relay(443, "192.168.0.1", null, null),
                new Relay(null, null, null, null),
                new Relay(null, null, null, "a"),
                new Relay(0, null, null, "a")); // a multi host name has port 0, not null
        assertThat(pool(ONE_RELAY, "f6").getPoolParams().getPoolMetadataUrl()).isNull();
    }

    @Test
    void moveInstantaneousRewards() {
        var toPot = (MoveInstataneous) certificate("8206" + "8201" + "05");
        assertThat(toPot.isTreasury()).isTrue();
        assertThat(toPot.isReserves()).isFalse();
        assertThat(toPot.getAccountingPotCoin()).isEqualTo(5);
        assertThat(toPot.getStakeCredentialCoinMap()).isEmpty();

        // A repeated key keeps its first position and its last value; a pot that is neither stays neither
        var toCredentials = (MoveInstataneous) certificate(
                "8206" + "8202" + "a3" + CRED + "01" + SCRIPT_CRED + "20" + CRED + "03");
        assertThat(toCredentials.isReserves()).isFalse();
        assertThat(toCredentials.isTreasury()).isFalse();
        assertThat(toCredentials.getAccountingPotCoin()).isNull();
        assertThat(toCredentials.getStakeCredentialCoinMap().entrySet()).hasSize(2);
        assertThat(toCredentials.getStakeCredentialCoinMap().keySet().iterator().next()).isEqualTo(stakeKey());
        assertThat(toCredentials.getStakeCredentialCoinMap().get(stakeKey())).isEqualTo(3);
    }

    @Test
    void keptLeniency() {
        // Items after the last position of a Conway certificate, and a number that is not one reads as null
        assertThat(certificate("8407" + CRED + "05" + "00")).isEqualTo(new RegCert(stakeKey(), BigInteger.valueOf(5)));
        assertThat(((RegCert) certificate("8307" + CRED + "6161")).getCoin()).isNull();
        // A Conway serializer does not read the tag; a Shelley one does, and counts the items
        assertThat(RegCertSerializer.INSTANCE.deserializeDI(item("83" + "6161" + CRED + "05")).getCoin()).isEqualTo(5);
        assertThatThrownBy(() -> StakeRegistrationSerializer.INSTANCE.deserializeDI(item("8201" + CRED)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> certificate("8300" + CRED + "00")).isInstanceOf(RuntimeException.class);
        // Tags are looked through, except that a null with a tag is not a null
        assertThat(certificate("d818" + "8200" + "d818" + CRED)).isEqualTo(new StakeRegistration(stakeKey()));
        assertThatThrownBy(() -> certificate("830f" + CRED + "d818f6")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void onlyAGenesisDelegationThatHasMoreThanItsTagFallsBackToEmpty() {
        assertThatThrownBy(() -> certificate("8105")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> certificate("80")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> certificate("8213" + CRED)).isInstanceOf(RuntimeException.class);
    }

    /**
     * The decoder this mapper replaced wrapped these numbers, read a bignum or a negative where the format has an
     * unsigned integer, and made an empty relay of an unknown one. No chain has them. The views reject them, and only
     * layout knowledge here could accept them, so they stand as listed differences (ADR 0003 I5).
     */
    @Test
    void numbersTheOldDecoderWrappedAreRejected() {
        String wrapsToZero = "1b0000000100000000";
        for (String cert : List.of(
                "82" + wrapsToZero + CRED, // certificate tag
                "8200" + "82" + wrapsToZero + HASH28, // credential tag
                "8309" + CRED + "81" + "1b0000000100000002", // DRep tag
                "8304" + HASH28 + "20", // negative epoch
                "8304" + HASH28 + "c24101", // bignum epoch
                "8206" + "82" + "20" + "05", // negative pot
                String.format(POOL, "81" + "8103", "f6"), // unknown relay tag
                String.format(POOL, "81" + "8420" + "f6f6f6", "f6"), // negative relay tag
                String.format(POOL, "81" + "8400" + "20" + "f6f6", "f6"))) // negative port
            assertThatThrownBy(() -> certificate(cert)).as(cert).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aBodyReadsItsCertificatesFromTheBytesReceived() throws Exception {
        String min = "008001" + "80";
        assertThat(body("a3" + min + "04" + "d90102" + "81" + "8200" + CRED).getCertificates())
                .containsExactly(new StakeRegistration(stakeKey()));
        // A repeated key means its last value, however it is encoded
        assertThat(body("a4" + min + "04" + "81" + "8201" + CRED + "1804" + "81" + "8200" + CRED).getCertificates())
                .containsExactly(new StakeRegistration(stakeKey()));
        // A tagged key is another key
        assertThat(body("a4" + min + "04" + "81" + "8200" + CRED + "c104" + "80").getCertificates())
                .containsExactly(new StakeRegistration(stakeKey()));
        // An empty set, including an indefinite one, whose decoded array holds its BREAK
        assertThat(body("a3" + min + "04" + "80").getCertificates()).isEmpty();
        assertThat(body("a3" + min + "04" + "9fff").getCertificates()).isEmpty();
    }

    /** The old block path counted a decoded indefinite array's BREAK as an item, and failed the block (yaci#113). */
    @Test
    void aBodyAcceptsIndefiniteCertificates() throws Exception {
        assertThat(body("a3008001" + "80" + "04" + "81" + "9f00" + "9f00" + HASH28 + "ffff").getCertificates())
                .containsExactly(new StakeRegistration(stakeKey()));
    }
}
