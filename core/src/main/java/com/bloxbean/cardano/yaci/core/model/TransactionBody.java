package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.VotingProcedures;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class TransactionBody {
    //Derived
    private String txHash;
    private String cbor; //tx body cbor

    private Set<TransactionInput> inputs;
    private List<TransactionOutput> outputs;
    private BigInteger fee;
    private long ttl;

    @Builder.Default
    private List<Certificate> certificates = new ArrayList<>();
    private Map<String, BigInteger> withdrawals;
    private Update update;
    private String auxiliaryDataHash;
    private long validityIntervalStart;

    @Builder.Default
    private List<Amount> mint = new ArrayList<>();
    private String scriptDataHash;

    private Set<TransactionInput> collateralInputs;
    private Set<String> requiredSigners;

    private int netowrkId;
    private TransactionOutput collateralReturn;
    private BigInteger totalCollateral;
    private Set<TransactionInput> referenceInputs;

    //Conway
    private VotingProcedures votingProcedures;
    private List<ProposalProcedure> proposalProcedures;
    private BigInteger currentTreasuryValue;
    private BigInteger donation;

    //Dijkstra era
    //14: every guard in order. Key-hash guards are also in requiredSigners, as before
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Credential> guards;
    //23
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SubTransaction> subTransactions;
    //24 (sub-transaction body only)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<RequiredTopLevelGuard> requiredTopLevelGuards;
    //25: reward account (hex, as in withdrawals) -> coin
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, BigInteger> directDeposits;
    //26: reward account (hex) -> balance interval
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, AccountBalanceInterval> accountBalanceIntervals;
    //27: reward account (hex) -> balance interval
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, AccountBalanceInterval> startingAccountBalanceIntervals;

    /**
     * Pre-Dijkstra constructor, kept so existing callers compile and behave unchanged.
     */
    public TransactionBody(String txHash,
                           String cbor,
                           Set<TransactionInput> inputs,
                           List<TransactionOutput> outputs,
                           BigInteger fee,
                           long ttl,
                           List<Certificate> certificates,
                           Map<String, BigInteger> withdrawals,
                           Update update,
                           String auxiliaryDataHash,
                           long validityIntervalStart,
                           List<Amount> mint,
                           String scriptDataHash,
                           Set<TransactionInput> collateralInputs,
                           Set<String> requiredSigners,
                           int netowrkId,
                           TransactionOutput collateralReturn,
                           BigInteger totalCollateral,
                           Set<TransactionInput> referenceInputs,
                           VotingProcedures votingProcedures,
                           List<ProposalProcedure> proposalProcedures,
                           BigInteger currentTreasuryValue,
                           BigInteger donation) {
        this(txHash, cbor, inputs, outputs, fee, ttl, certificates, withdrawals, update, auxiliaryDataHash, validityIntervalStart, mint, scriptDataHash, collateralInputs, requiredSigners, netowrkId, collateralReturn, totalCollateral, referenceInputs, votingProcedures, proposalProcedures, currentTreasuryValue, donation,
                null, null, null, null, null, null);
    }
}
