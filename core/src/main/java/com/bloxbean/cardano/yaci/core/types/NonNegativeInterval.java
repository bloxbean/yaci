package com.bloxbean.cardano.yaci.core.types;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;

@Getter
@EqualsAndHashCode
@ToString
@Slf4j
public class NonNegativeInterval extends UnitInterval {

    public NonNegativeInterval() {
        super();
    }

    public NonNegativeInterval(BigInteger numerator, BigInteger denominator) {
        super(numerator, denominator);
        if(numerator.compareTo(BigInteger.ZERO) < 0 || denominator.compareTo(BigInteger.ZERO) <= 0) {
            //Just a warning, don't throw exception
            log.warn("Numerator should not be non-negative and Denominator should be a positive int. Numerator: {}, Denominator: {}", numerator, denominator);
        }
    }

    public static NonNegativeInterval fromString(String str) {
        String[] parts = str.split("/");
        return new NonNegativeInterval(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }
}

