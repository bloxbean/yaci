package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

import java.math.BigInteger;

/**
 * Dijkstra era {@code account_balance_interval} (tx body keys 26 and 27): bounds on a reward account's balance.
 * <pre>
 * account_balance_interval =
 *     [inclusive_lower_bound : coin, exclusive_upper_bound : coin / nil]
 *   / [inclusive_lower_bound : coin / nil, exclusive_upper_bound : coin]
 *   / coin
 * </pre>
 * The {@code coin} form is an exact balance: {@code exactBalance} is set and the bounds are
 * {@code [exactBalance, exactBalance + 1)}, so code that only reads the bounds sees the same constraint.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class AccountBalanceInterval {
    //null when unbounded
    private BigInteger inclusiveLowerBound;
    //null when unbounded
    private BigInteger exclusiveUpperBound;
    //set only for the coin form
    private BigInteger exactBalance;

    public static AccountBalanceInterval exact(BigInteger balance) {
        return new AccountBalanceInterval(balance, balance.add(BigInteger.ONE), balance);
    }
}
