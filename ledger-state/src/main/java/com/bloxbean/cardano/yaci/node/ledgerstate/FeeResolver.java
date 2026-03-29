package com.bloxbean.cardano.yaci.node.ledgerstate;

import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the actual fee paid by a transaction.
 * For valid transactions, returns the declared fee.
 * For phase-2 failed transactions, returns the collateral fee:
 *   1. Babbage+: totalCollateral field
 *   2. Alonzo fallback: sum(collateral inputs) - collateral return lovelace
 *
 * Matches yaci-store's FeeResolver logic.
 */
public class FeeResolver {
    private static final Logger log = LoggerFactory.getLogger(FeeResolver.class);

    private final UtxoState utxoState; // nullable

    public FeeResolver(UtxoState utxoState) {
        this.utxoState = utxoState;
    }

    public BigInteger resolveFee(TransactionBody tx, boolean invalid) {
        if (!invalid) return tx.getFee();
        return resolveCollateralFee(tx);
    }

    private BigInteger resolveCollateralFee(TransactionBody tx) {
        // Tier 1: totalCollateral field (Babbage+)
        if (tx.getTotalCollateral() != null && tx.getTotalCollateral().signum() > 0)
            return tx.getTotalCollateral();

        // Tier 2: Alonzo fallback — resolve from collateral inputs and return
        if (utxoState == null || !utxoState.isEnabled()) {
            log.warn("No UTXO state available for Alonzo collateral resolution, using declared fee");
            return tx.getFee();
        }

        BigInteger totalInput = BigInteger.ZERO;
        Set<TransactionInput> collateralInputs = tx.getCollateralInputs();
        if (collateralInputs != null) {
            for (TransactionInput input : collateralInputs) {
                Optional<Utxo> utxo = utxoState.getUtxoSpentOrUnspent(
                        new Outpoint(input.getTransactionId(), input.getIndex()));
                if (utxo.isPresent()) {
                    totalInput = totalInput.add(utxo.get().lovelace());
                } else {
                    log.warn("Collateral UTXO not found: {}#{}, using declared fee",
                            input.getTransactionId(), input.getIndex());
                    return tx.getFee();
                }
            }
        }

        BigInteger totalOutput = BigInteger.ZERO;
        var collateralReturn = tx.getCollateralReturn();
        if (collateralReturn != null && collateralReturn.getAmounts() != null) {
            totalOutput = collateralReturn.getAmounts().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .findFirst()
                    .map(Amount::getQuantity)
                    .orElse(BigInteger.ZERO);
        }

        return totalInput.subtract(totalOutput);
    }
}
