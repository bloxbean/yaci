package com.bloxbean.cardano.yaci.core.types;

import lombok.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public class UnitInterval {
    private static MathContext defaultMathContext = new MathContext(35);

    private BigInteger numerator;
    private BigInteger denominator;

    public String toString() {
        return numerator + "/" + denominator;
    }

    public BigDecimal safeRatio() {
        return safeRatio(numerator, denominator);
    }

    public BigDecimal safeRatio(MathContext mathContext) {
        return safeRatio(numerator, denominator, mathContext);
    }

    public static UnitInterval fromString(String str) {
        String[] parts = str.split("/");
        return new UnitInterval(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    private static BigDecimal safeRatio(BigInteger numerator, BigInteger denominator) {
        return safeRatio(numerator, denominator, defaultMathContext);
    }

    private static BigDecimal safeRatio(BigInteger numerator, BigInteger denominator, MathContext mathContext) {
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

