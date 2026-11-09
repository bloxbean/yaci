package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.RawAccountBalanceInterval;
import com.bloxbean.cardano.client.transaction.raw.RawCredential;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.client.transaction.raw.RawProposal;
import com.bloxbean.cardano.client.transaction.raw.RawTopLevelGuard;
import com.bloxbean.cardano.client.transaction.raw.RawTx;
import com.bloxbean.cardano.client.transaction.raw.RawTxBody;
import com.bloxbean.cardano.client.transaction.raw.RawWithdrawal;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.model.AccountBalanceInterval;
import com.bloxbean.cardano.yaci.core.model.AuxData;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.SubTransaction;
import com.bloxbean.cardano.yaci.core.model.TopLevelGuard;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.ProposalProcedureSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.VotingProceduresSerializer;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps bodies read by {@link RawTxBody} to Yaci's model (ADR 0003 D8). A Dijkstra body's fields come from the view's
 * Dijkstra accessors, which are empty in earlier eras (D4b; experimental, as Dijkstra is not final): its required
 * signers are the key hashes of its key guards, as the ledger derives them, and its sub-transactions are mapped as a
 * block's transactions are.
 */
@Slf4j
public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    /**
     * @param di      the body decoded from {@code txBytes}; the fields are read from {@code txBytes}
     * @param txBytes the body as encoded
     * @return the body, read as Conway: in any other era, only a key that has changed meaning reads differently
     * @deprecated {@code di} is not used. Use {@link #read(byte[], Era)}, or {@link BlockSerializer} for a block.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    public TransactionBody deserializeDI(DataItem di, byte[] txBytes) {
        return read(txBytes, Era.Conway);
    }

    /**
     * @param txBytes the body as encoded
     * @param era     the era of its block
     * @return the body
     */
    public TransactionBody read(byte[] txBytes, Era era) {
        return read(RawTxBody.tolerant(CborSpan.at(txBytes, 0), era), txBytes);
    }

    /**
     * @param body  a body, read in its era: a sub-transaction's at sub level, without the top-level-only fields
     * @param bytes the body as encoded, whose hash is its id
     */
    private TransactionBody read(RawTxBody body, byte[] bytes) {
        Era era = body.era();
        TransactionBody.TransactionBodyBuilder builder = TransactionBody.builder();

        //derive
        builder.txHash(TxUtil.calculateTxHash(bytes));
        if (YaciConfig.INSTANCE.isReturnTxBodyCbor() || YaciConfig.INSTANCE.isReturnFullTxCbor())
            builder.cbor(HexUtil.encodeHexString(bytes));

        builder.inputs(inputs(body.inputs(), new LinkedHashSet<>()));
        List<TransactionOutput> outputs = new ArrayList<>();
        for (RawOutput output : body.outputs())
            outputs.add(TransactionOutputSerializer.INSTANCE.read(output));
        builder.outputs(outputs);

        body.fee().ifPresent(builder::fee);
        body.ttl().ifPresent(builder::ttl);

        List<CborSpan> certificates = body.certificateItems();
        if (!certificates.isEmpty()) {
            List<Certificate> read = new ArrayList<>(certificates.size());
            for (CborSpan certificate : certificates)
                read.add(CertificateSerializer.INSTANCE.read(certificate, era));
            builder.certificates(read);
        }

        List<RawWithdrawal> withdrawals = body.withdrawals();
        if (!withdrawals.isEmpty())
            builder.withdrawals(WithdrawalsSerializer.INSTANCE.read(withdrawals));

        body.update().map(UpdateSerializer.INSTANCE::read).ifPresent(builder::update);
        body.auxDataHash().map(HexUtil::encodeHexString).ifPresent(builder::auxiliaryDataHash);
        body.validityStart().ifPresent(builder::validityIntervalStart);
        builder.mint(TransactionOutputSerializer.assets(body.mint()));
        body.scriptDataHash().map(HexUtil::encodeHexString).ifPresent(builder::scriptDataHash);
        body.collateral().ifPresent(inputs -> builder.collateralInputs(inputs(inputs, new HashSet<>())));
        body.requiredSigners().ifPresent(signers -> builder.requiredSigners(hex(signers)));
        body.guards().ifPresent(guards -> builder.guards(credentials(guards)).requiredSigners(keyHashes(guards)));
        body.networkId().ifPresent(id -> {
            if (id == 0 || id == 1)
                builder.netowrkId(id);
            else
                log.error("Invalid networkId value : " + id);
        });
        body.collateralReturn().map(TransactionOutputSerializer.INSTANCE::read).ifPresent(builder::collateralReturn);
        body.totalCollateral().ifPresent(builder::totalCollateral);
        body.referenceInputs().ifPresent(inputs -> builder.referenceInputs(inputs(inputs, new LinkedHashSet<>())));

        body.votingProcedures().map(VotingProceduresSerializer.INSTANCE::read).ifPresent(builder::votingProcedures);
        body.proposalProcedures().ifPresent(procedures -> builder.proposalProcedures(proposals(procedures)));
        body.currentTreasury().ifPresent(builder::currentTreasuryValue);
        body.donation().ifPresent(builder::donation);

        // Absent and empty read alike from these views, and a valid body has none empty: both are null.
        List<RawTx> subTransactions = body.subTransactions();
        if (!subTransactions.isEmpty())
            builder.subTransactions(subTransactions(subTransactions, era));
        List<RawTopLevelGuard> topLevelGuards = body.requiredTopLevelGuards();
        if (!topLevelGuards.isEmpty())
            builder.requiredTopLevelGuards(topLevelGuards(topLevelGuards));
        List<RawWithdrawal> directDeposits = body.directDeposits();
        if (!directDeposits.isEmpty())
            builder.directDeposits(WithdrawalsSerializer.INSTANCE.read(directDeposits));
        builder.accountBalanceIntervals(intervals(body.accountBalanceIntervals()));
        builder.startingAccountBalanceIntervals(intervals(body.startingAccountBalanceIntervals()));

        return builder.build();
    }

    private static List<Credential> credentials(List<RawCredential> credentials) {
        List<Credential> read = new ArrayList<>(credentials.size());
        for (RawCredential credential : credentials)
            read.add(CertificateSerializer.credential(credential));
        return read;
    }

    private static Set<String> keyHashes(List<RawCredential> guards) {
        Set<String> set = new HashSet<>();
        for (RawCredential guard : guards)
            if (!guard.isScript())
                set.add(HexUtil.encodeHexString(guard.hash()));
        return set;
    }

    /**
     * Each sub-transaction's parts are read as those of a transaction of its block, its body from CCL's view of it at
     * sub level, which has no sub-transactions of its own.
     */
    private List<SubTransaction> subTransactions(List<RawTx> transactions, Era era) {
        List<SubTransaction> read = new ArrayList<>(transactions.size());
        for (RawTx transaction : transactions) {
            TransactionBody body = read(transaction.body(era), transaction.body().bytes());
            AuxData auxData = transaction.auxData().map(aux -> BlockSerializer.auxData(aux, era,
                    bytes -> BlockSerializer.hashMatches(body.getAuxiliaryDataHash(), bytes))).orElse(null);
            read.add(new SubTransaction(body, BlockSerializer.witnessSet(transaction.witnessSet(), era), auxData));
        }
        return read;
    }

    private static List<TopLevelGuard> topLevelGuards(List<RawTopLevelGuard> guards) {
        List<TopLevelGuard> read = new ArrayList<>(guards.size());
        for (RawTopLevelGuard guard : guards)
            read.add(new TopLevelGuard(CertificateSerializer.credential(guard.credential()),
                    guard.data() == null ? null : DataItemIsolation.datum(guard.data().span())));
        return read;
    }

    /** By reward account in hex, in the order received, as withdrawals are; null for none. */
    private static Map<String, AccountBalanceInterval> intervals(List<RawAccountBalanceInterval> intervals) {
        if (intervals.isEmpty())
            return null;
        Map<String, AccountBalanceInterval> read = new LinkedHashMap<>();
        for (RawAccountBalanceInterval interval : intervals)
            read.put(HexUtil.encodeHexString(interval.rewardAccount()),
                    new AccountBalanceInterval(interval.exact(), interval.lower(), interval.upper()));
        return Collections.unmodifiableMap(read);
    }

    private static Set<TransactionInput> inputs(List<RawInput> inputs, Set<TransactionInput> set) {
        for (RawInput input : inputs)
            set.add(TransactionInputSerializer.INSTANCE.read(input));
        return set;
    }

    private static Set<String> hex(List<byte[]> hashes) {
        Set<String> set = new HashSet<>();
        for (byte[] hash : hashes)
            set.add(HexUtil.encodeHexString(hash));
        return set;
    }

    private static List<ProposalProcedure> proposals(List<RawProposal> procedures) {
        List<ProposalProcedure> read = new ArrayList<>(procedures.size());
        for (RawProposal procedure : procedures)
            read.add(ProposalProcedureSerializer.INSTANCE.read(procedure));
        return read;
    }
}
