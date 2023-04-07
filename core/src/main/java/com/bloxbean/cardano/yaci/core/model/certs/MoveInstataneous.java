package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.yaci.core.model.jackson.StakeCredentialDeserializer;
import com.bloxbean.cardano.yaci.core.model.jackson.StakeCredentialSerializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.*;

import java.math.BigInteger;
import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class MoveInstataneous implements Certificate {
    private final CertificateType type = CertificateType.MOVE_INSTATENEOUS_REWARDS_CERT;

    //determines where the funds are drawn from
    private boolean reserves;
    private boolean treasury;

    private BigInteger accountingPotCoin; //the funds are given to the other accounting pot
    @JsonDeserialize(keyUsing = StakeCredentialDeserializer.class)
    @JsonSerialize(keyUsing = StakeCredentialSerializer.class)
    private Map<StakeCredential, BigInteger> stakeCredentialCoinMap; //funds are moved to stake credentials
}
