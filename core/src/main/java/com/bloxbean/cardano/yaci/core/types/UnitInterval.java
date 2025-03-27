package com.bloxbean.cardano.yaci.core.types;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

@Getter
@AllArgsConstructor
@EqualsAndHashCode
@ToString
public class UnitInterval {
    private static MathContext defaultMathContext = new MathContext(35);

    private BigInteger numerator;
    private BigInteger denominator;

    public String toString() {
        return numerator + "/" + denominator;
    }

    public static UnitInterval fromString(String str) {
        String[] parts = str.split("/");
        return new UnitInterval(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    public static BigDecimal safeRatio(BigInteger numerator, BigInteger denominator) {
        return safeRatio(numerator, denominator, defaultMathContext);
    }

    public static BigDecimal safeRatio(BigInteger numerator, BigInteger denominator, MathContext mathContext) {
        if (isInvalidUnitInterval(numerator, denominator)) {
            return BigDecimal.ZERO;
        }

        var numeratorBD = new BigDecimal(numerator);
        var denominatorBD = new BigDecimal(denominator);

        return numeratorBD.divide(denominatorBD, mathContext);
    }

    private static boolean isInvalidUnitInterval(BigInteger numerator, BigInteger denominator) {
        boolean denominatorIsZero = denominator != null && denominator.equals(BigInteger.ZERO);
        return numerator == null || denominator == null || denominatorIsZero;

        //Ideally, we should also check numerator <= denominator and throw exception
        //But, the caller is expected to pass the correct values to the safeRatio method
    }
}

