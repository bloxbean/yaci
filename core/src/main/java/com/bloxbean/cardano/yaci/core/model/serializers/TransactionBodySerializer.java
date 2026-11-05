package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.common.cbor.CborSpan;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawInput;
import com.bloxbean.cardano.client.transaction.raw.RawOutput;
import com.bloxbean.cardano.client.transaction.raw.RawProposal;
import com.bloxbean.cardano.client.transaction.raw.RawTxBody;
import com.bloxbean.cardano.client.transaction.raw.RawWithdrawal;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps bodies read by {@link RawTxBody} to Yaci's model (ADR 0003 D8).
 */
@Slf4j
public enum TransactionBodySerializer implements Serializer<TransactionBody> {
    INSTANCE;

    /**
     * @param di      the body decoded from {@code txBytes}; the fields are read from {@code txBytes}
     * @param txBytes the body as encoded
     * @return the body, read in the newest era: in any other, only a key that has changed meaning reads differently
     */
    public TransactionBody deserializeDI(DataItem di, byte[] txBytes) {
        return read(txBytes, EraSpec.newest());
    }

    TransactionBody read(byte[] txBytes, Era era) {
        RawTxBody body = RawTxBody.tolerant(CborSpan.at(txBytes, 0), era);
        TransactionBody.TransactionBodyBuilder builder = TransactionBody.builder();

        //derive
        builder.txHash(TxUtil.calculateTxHash(txBytes));
        if (YaciConfig.INSTANCE.isReturnTxBodyCbor() || YaciConfig.INSTANCE.isReturnFullTxCbor())
            builder.cbor(HexUtil.encodeHexString(txBytes));

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
                read.add(CertificateSerializer.INSTANCE.read(certificate));
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

        return builder.build();
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
