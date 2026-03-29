package com.bloxbean.cardano.yaci.node.scalusbridge;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.ledger.LedgerContext;
import com.bloxbean.cardano.client.ledger.LedgerStateValidator;
import com.bloxbean.cardano.client.ledger.rule.CertificateValidationRule;
import com.bloxbean.cardano.client.ledger.rule.GovernanceValidationRule;
import com.bloxbean.cardano.client.ledger.rule.LedgerRule;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciAccountsSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciCommitteeSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciDRepsSlice;
import com.bloxbean.cardano.client.ledger.slice.yaci.YaciPoolsSlice;
import com.bloxbean.cardano.client.spec.NetworkId;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.yaci.node.api.account.LedgerStateProvider;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionValidator;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationError;
import com.bloxbean.cardano.yaci.node.ledgerrules.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scalus.bloxbean.ScriptSupplier;
import scalus.cardano.ledger.SlotConfig;

import java.util.*;

/**
 * {@link TransactionValidator} implementation using Scalus for full Cardano ledger rule validation.
 * Delegates to {@link LedgerBridge} which calls Scalus's CardanoMutator.transit().
 *
 * <p>Scalus v0.16.0 handles all DELEG rules (registration, deregistration, deposit/refund amounts,
 * reward balance checks, withdrawal validation) with proper intra-tx state tracking. CCL supplementary
 * rules run after Scalus for gaps it doesn't cover (GOVCERT, delegatee existence, governance).
 */
public class ScalusBasedTransactionValidator implements TransactionValidator {

    private static final Logger log = LoggerFactory.getLogger(ScalusBasedTransactionValidator.class);

    // CCL supplementary rules — only for gaps Scalus doesn't cover (GOVCERT + delegatee + governance)
    private static final List<LedgerRule> SUPPLEMENTARY_RULES = List.of(
            new CertificateValidationRule(),
            new GovernanceValidationRule()
    );

    private final ProtocolParams protocolParams;
    private final ScriptSupplier scriptSupplier;
    private final SlotConfig scalusSlotConfig;
    private final int networkId;
    private final LedgerStateProvider ledgerStateProvider;

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId) {
        this(protocolParams, scriptSupplier, slotConfig, networkId, null);
    }

    public ScalusBasedTransactionValidator(ProtocolParams protocolParams,
                                           com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier,
                                           com.bloxbean.cardano.client.common.model.SlotConfig slotConfig,
                                           int networkId,
                                           LedgerStateProvider ledgerStateProvider) {
        this.protocolParams = protocolParams;
        if (scriptSupplier != null)
            this.scriptSupplier = new ScalusScriptSupplier(scriptSupplier);
        else
            this.scriptSupplier = null;
        this.networkId = networkId;
        this.ledgerStateProvider = ledgerStateProvider;

        this.scalusSlotConfig = new scalus.cardano.ledger.SlotConfig(
                slotConfig.getZeroTime(),
                slotConfig.getZeroSlot(),
                slotConfig.getSlotLength());
    }

    @Override
    public ValidationResult validate(byte[] txCbor, Set<Utxo> inputUtxos) {
        try {
            // Extract currentSlot from tx validity interval
            long currentSlot = 0;
            Transaction tx = null;
            try {
                tx = Transaction.deserialize(txCbor);
                if (tx.getBody().getValidityStartInterval() > 0) {
                    currentSlot = tx.getBody().getValidityStartInterval();
                }
            } catch (Exception e) {
                // If we can't deserialize to extract slot, use 0 and let Scalus handle it
            }

            TransitResult result = LedgerBridge.validate(
                    txCbor, protocolParams, inputUtxos, currentSlot,
                    scalusSlotConfig, networkId, scriptSupplier, ledgerStateProvider);

            if (!result.isSuccess()) {
                ValidationError error = mapError(result);
                return ValidationResult.failure(error);
            }

            // STEP 2: Scalus passed — run CCL supplementary rules for gaps
            // (GOVCERT certs, delegatee existence, governance validation)
            if (tx != null && ledgerStateProvider != null) {
                ValidationResult cclResult = runSupplementaryRules(tx, currentSlot);
                if (!cclResult.valid()) {
                    return cclResult;
                }
            }

            return ValidationResult.success();

        } catch (Exception e) {
            return ValidationResult.failure(new ValidationError(
                    "InternalError",
                    "Validation error: " + e.getMessage(),
                    ValidationError.Phase.PHASE_1));
        }
    }

    /**
     * Run CCL supplementary rules for validation gaps not covered by Scalus:
     * GOVCERT certs, delegatee existence checks, governance proposals/voting.
     */
    private ValidationResult runSupplementaryRules(Transaction tx, long currentSlot) {
        try {
            LedgerContext ctx = LedgerContext.builder()
                    .protocolParams(protocolParams)
                    .currentSlot(currentSlot)
                    .networkId(networkId == 1 ? NetworkId.MAINNET : NetworkId.TESTNET)
                    .accountsSlice(new YaciAccountsSlice(ledgerStateProvider))
                    .poolsSlice(new YaciPoolsSlice(ledgerStateProvider))
                    .drepsSlice(new YaciDRepsSlice(ledgerStateProvider))
                    .committeeSlice(new YaciCommitteeSlice(ledgerStateProvider))
                    // ProposalsSlice: null for now — governance proposal checks skipped
                    .build();

            LedgerStateValidator validator = LedgerStateValidator.builder()
                    .customRules(SUPPLEMENTARY_RULES)
                    .build();

            var cclResult = validator.validate(ctx, tx);
            if (!cclResult.isValid()) {
                // Map first CCL error to yaci ValidationResult
                var cclError = cclResult.getErrors().get(0);
                return ValidationResult.failure(new ValidationError(
                        cclError.getRule(),
                        cclError.getMessage(),
                        cclError.getPhase() == com.bloxbean.cardano.client.api.model.ValidationError.Phase.PHASE_2
                                ? ValidationError.Phase.PHASE_2
                                : ValidationError.Phase.PHASE_1));
            }
        } catch (Exception e) {
            log.warn("CCL supplementary rule validation failed, allowing tx: {}", e.getMessage());
            // Don't reject on CCL internal errors — Scalus already passed
        }
        return ValidationResult.success();
    }

    private ValidationError mapError(TransitResult result) {
        String className = result.errorClassName() != null ? result.errorClassName() : "Unknown";
        String message = result.errorMessage();

        ValidationError.Phase phase = className.contains("PlutusScript") || className.contains("Script")
                ? ValidationError.Phase.PHASE_2
                : ValidationError.Phase.PHASE_1;

        String rule = className.replace("Exception", "").replace("$", "");

        return new ValidationError(rule, message, phase);
    }
}
