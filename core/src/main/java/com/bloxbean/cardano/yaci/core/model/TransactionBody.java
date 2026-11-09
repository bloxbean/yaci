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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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
    /**
     * The required signers' key hashes, in hex. In Dijkstra, the hashes of the key credentials among
     * {@link #guards}, as the ledger derives them: null without guards, empty if the guards hold no key credential.
     */
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

    //Dijkstra
    /**
     * The guards (key 14), key and script credentials in encoded order; null if absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Credential> guards;

    /**
     * The sub-transactions (key 23), in encoded order; null if absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SubTransaction> subTransactions;

    /**
     * The required top-level guards (key 24), in encoded order; null if absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<TopLevelGuard> requiredTopLevelGuards;

    /**
     * The direct deposits (key 25), by reward account in hex as {@link #withdrawals} are; null if absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, BigInteger> directDeposits;

    /**
     * The account balance intervals (key 26), by reward account in hex as {@link #withdrawals} are; null if absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, AccountBalanceInterval> accountBalanceIntervals;

    /**
     * The starting account balance intervals (key 27), by reward account in hex as {@link #withdrawals} are; null if
     * absent.
     * <p>
     * Experimental: Dijkstra is not final; this may change in any release.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, AccountBalanceInterval> startingAccountBalanceIntervals;

    /** Kept for source and binary compatibility: the Dijkstra fields are null. Use the builder for them. */
    public TransactionBody(String txHash, String cbor, Set<TransactionInput> inputs, List<TransactionOutput> outputs,
                           BigInteger fee, long ttl, List<Certificate> certificates,
                           Map<String, BigInteger> withdrawals, Update update, String auxiliaryDataHash,
                           long validityIntervalStart, List<Amount> mint, String scriptDataHash,
                           Set<TransactionInput> collateralInputs, Set<String> requiredSigners, int netowrkId,
                           TransactionOutput collateralReturn, BigInteger totalCollateral,
                           Set<TransactionInput> referenceInputs, VotingProcedures votingProcedures,
                           List<ProposalProcedure> proposalProcedures, BigInteger currentTreasuryValue,
                           BigInteger donation) {
        this(txHash, cbor, inputs, outputs, fee, ttl, certificates, withdrawals, update, auxiliaryDataHash,
                validityIntervalStart, mint, scriptDataHash, collateralInputs, requiredSigners, netowrkId,
                collateralReturn, totalCollateral, referenceInputs, votingProcedures, proposalProcedures,
                currentTreasuryValue, donation, null, null, null, null, null, null);
    }
}
