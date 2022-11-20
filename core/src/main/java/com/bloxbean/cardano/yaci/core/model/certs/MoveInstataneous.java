package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.*;

import java.math.BigInteger;
import java.util.Map;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@ToString
@Builder
public class MoveInstataneous implements Certificate {
    //determines where the funds are drawn from
    private boolean reserves;
    private boolean treasury;

    private BigInteger accountingPotCoin; //the funds are given to the other accounting pot
    private Map<StakeCredential, BigInteger> stakeCredentialCoinMap; //funds are moved to stake credentials
}
