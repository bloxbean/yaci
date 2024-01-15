package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.VotingProcedures;
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
}
