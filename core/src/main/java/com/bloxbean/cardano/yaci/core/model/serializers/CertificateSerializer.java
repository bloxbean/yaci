package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.RawAnchor;
import com.bloxbean.cardano.client.transaction.raw.RawCertificate;
import com.bloxbean.cardano.client.transaction.raw.RawCredential;
import com.bloxbean.cardano.client.transaction.raw.RawMir;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.*;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.DrepSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maps certificates read by {@link RawCertificate} to Yaci's models (ADR 0003 D8). The reader knows the format; this
 * class knows the models, and which certificates are read exactly.
 */
public enum CertificateSerializer implements Serializer<Certificate> {
    INSTANCE;

    @Override
    public Certificate deserializeDI(DataItem certArrayDI) {
        Objects.requireNonNull(certArrayDI);
        return read(RawModels.span(certArrayDI));
    }

    /**
     * @param span a certificate as encoded
     * @return the certificate, or an empty one for a genesis delegation that fails to parse (ADR 0003 section 12)
     */
    public Certificate read(CborSpan span) {
        CertKind kind = CertKind.of(span).orElseThrow(() ->
                new CborRuntimeException("Certificate deserialization failed. Unknown type : " + span));
        if (kind != CertKind.GENESIS_KEY_DELEGATION)
            return read(kind, span);

        // What is too short for any certificate is not a genesis delegation that failed: stake registration is the
        // shortest, and this rejects it.
        RawCertificate.lenient(CertKind.STAKE_REGISTRATION, span);
        try {
            return GenesisKeyDelegationSerializer.INSTANCE.read(span);
        } catch (Exception e) {
            e.printStackTrace();
            return new GenesisKeyDelegation(null, null, null);
        }
    }

    /**
     * @param kind the kind to read it as
     * @param span a certificate as encoded
     * @return the certificate
     */
    public Certificate read(CertKind kind, CborSpan span) {
        RawCertificate cert = RawCertificate.lenient(kind, span);
        return switch (kind) {
            case STAKE_REGISTRATION -> new StakeRegistration(stakeCredential(cert.exact().credential()));
            case STAKE_DEREGISTRATION -> new StakeDeregistration(stakeCredential(cert.exact().credential()));
            case STAKE_DELEGATION -> new StakeDelegation(stakeCredential(cert.exact().credential()),
                    new StakePoolId(cert.poolKeyHash()));
            case POOL_REGISTRATION -> PoolRegistrationSerializer.read(cert.exact());
            case POOL_RETIREMENT -> new PoolRetirement(hex(cert.exact().poolKeyHash()), cert.epoch());
            case GENESIS_KEY_DELEGATION -> new GenesisKeyDelegation(hex(cert.exact().genesisHash()),
                    hex(cert.genesisDelegateHash()), hex(cert.vrfKeyHash()));
            case MOVE_INSTANTANEOUS_REWARDS -> moveInstantaneous(cert.exact().moveInstantaneousReward());
            case REG_CERT -> new RegCert(stakeCredential(cert.credential()), cert.coin());
            case UNREG_CERT -> new UnregCert(stakeCredential(cert.credential()), cert.coin());
            case VOTE_DELEG_CERT -> new VoteDelegCert(stakeCredential(cert.credential()),
                    DrepSerializer.INSTANCE.read(cert.drep()));
            case STAKE_VOTE_DELEG_CERT -> new StakeVoteDelegCert(stakeCredential(cert.credential()),
                    hex(cert.poolKeyHash()), DrepSerializer.INSTANCE.read(cert.drep()));
            case STAKE_REG_DELEG_CERT -> new StakeRegDelegCert(stakeCredential(cert.credential()),
                    hex(cert.poolKeyHash()), cert.coin());
            case VOTE_REG_DELEG_CERT -> new VoteRegDelegCert(stakeCredential(cert.credential()),
                    DrepSerializer.INSTANCE.read(cert.drep()), cert.coin());
            case STAKE_VOTE_REG_DELEG_CERT -> new StakeVoteRegDelegCert(stakeCredential(cert.credential()),
                    hex(cert.poolKeyHash()), DrepSerializer.INSTANCE.read(cert.drep()), cert.coin());
            case AUTH_COMMITTEE_HOT_CERT -> new AuthCommitteeHotCert(credential(cert.credential()),
                    credential(cert.hotCredential()));
            case RESIGN_COMMITTEE_COLD_CERT -> new ResignCommitteeColdCert(credential(cert.credential()),
                    anchor(cert.anchor()));
            case REG_DREP_CERT -> new RegDrepCert(credential(cert.credential()), cert.coin(), anchor(cert.anchor()));
            case UNREG_DREP_CERT -> new UnregDrepCert(credential(cert.credential()), cert.coin());
            case UPDATE_DREP_CERT -> new UpdateDrepCert(credential(cert.credential()), anchor(cert.anchor()));
        };
    }

    static StakeCredential stakeCredential(RawCredential credential) {
        return credential.isScript() ? StakeCredential.fromScriptHash(credential.hash())
                : StakeCredential.fromKeyHash(credential.hash());
    }

    private static Credential credential(RawCredential credential) {
        return credential.isScript() ? Credential.fromScriptHash(credential.hash())
                : Credential.fromKeyHash(credential.hash());
    }

    private static Anchor anchor(Optional<RawAnchor> anchor) {
        return anchor.map(item -> new Anchor(item.url(), hex(item.dataHash()))).orElse(null);
    }

    private static MoveInstataneous moveInstantaneous(RawMir mir) {
        int pot = mir.pot();
        Map<StakeCredential, BigInteger> targets = new LinkedHashMap<>();
        BigInteger coin = null;
        if (mir.movesToCredentials()) {
            for (Map.Entry<RawCredential, BigInteger> target : mir.targets())
                targets.put(stakeCredential(target.getKey()), target.getValue());
        } else {
            coin = mir.coin();
        }
        return new MoveInstataneous(pot == 0, pot == 1, coin, targets);
    }

    private static String hex(byte[] bytes) {
        return HexUtil.encodeHexString(bytes);
    }
}
