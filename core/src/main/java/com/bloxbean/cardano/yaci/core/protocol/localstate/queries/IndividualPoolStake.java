package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;

@Getter
@AllArgsConstructor
@ToString
public class IndividualPoolStake {
    private String stakePoolKeyHash;

    //numerator/denominator is the quotient representing the stake fractions
    private BigInteger numerator;
    private BigInteger denominator;

    private String vrfKeyHash;
    /** The pool's total stake in lovelace, from N2C V21; null before. */
    private BigInteger totalPoolStake;

    /** Kept for existing callers: no total pool stake (N2C V20 and earlier). */
    public IndividualPoolStake(String stakePoolKeyHash, BigInteger numerator, BigInteger denominator,
                               String vrfKeyHash) {
        this(stakePoolKeyHash, numerator, denominator, vrfKeyHash, null);
    }
}
