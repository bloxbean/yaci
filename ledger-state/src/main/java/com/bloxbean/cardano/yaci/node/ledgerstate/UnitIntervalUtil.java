package com.bloxbean.cardano.yaci.node.ledgerstate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * Utility for precise unit-interval arithmetic (e.g., pool margin).
 */
class UnitIntervalUtil {
    private static final MathContext PRECISION = new MathContext(35);

    static BigDecimal safeRatio(BigInteger numerator, BigInteger denominator) {
        if (denominator == null || denominator.signum() == 0) return BigDecimal.ZERO;
        if (numerator == null) return BigDecimal.ZERO;
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), PRECISION);
    }
}
