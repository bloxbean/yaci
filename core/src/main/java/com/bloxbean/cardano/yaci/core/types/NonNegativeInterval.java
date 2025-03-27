package com.bloxbean.cardano.yaci.core.types;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;

@Getter
@EqualsAndHashCode
@ToString
@Slf4j
public class NonNegativeInterval extends UnitInterval {

    public NonNegativeInterval(BigInteger numerator, BigInteger denominator) {
        super(numerator, denominator);
        if(numerator.compareTo(BigInteger.ZERO) < 0 || denominator.compareTo(BigInteger.ZERO) < 0) {
            //Just a warning, don't throw exception
            log.warn("Numerator or Denominator should be non-negative. Numerator: {}, Denominator: {}", numerator, denominator);
        }
    }
}

