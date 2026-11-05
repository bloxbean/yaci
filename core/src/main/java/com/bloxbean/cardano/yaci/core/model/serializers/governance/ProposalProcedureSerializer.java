package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.transaction.raw.EraSpec;
import com.bloxbean.cardano.client.transaction.raw.RawProposal;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

/** Maps proposal procedures read by {@link RawProposal} to Yaci's model (ADR 0003 D8). */
public enum ProposalProcedureSerializer implements Serializer<ProposalProcedure> {
    INSTANCE;

    /**
     * @param bytes a proposal procedure, exactly one CBOR item
     * @return the proposal, read in the newest era; null for empty input
     */
    @Override
    public ProposalProcedure deserialize(byte[] bytes) {
        if (bytes.length == 0)
            return null;
        return read(RawProposal.lenient(CborSerializationUtil.span(bytes), EraSpec.newest()));
    }

    /**
     * @deprecated A decoded proposal procedure has lost its source encoding. Use {@link #deserialize(byte[])} with its
     * bytes, or {@link #read(RawProposal)}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public ProposalProcedure deserializeDI(DataItem di) {
        return read(RawProposal.lenient(RawModels.span(di), EraSpec.newest()));
    }

    /**
     * @param proposal a proposal procedure as received
     * @return the proposal
     */
    public ProposalProcedure read(RawProposal proposal) {
        return new ProposalProcedure(proposal.deposit(), HexUtil.encodeHexString(proposal.rewardAccount()),
                GovActionSerializer.INSTANCE.read(proposal.govAction()),
                AnchorSerializer.INSTANCE.read(proposal.anchor()));
    }
}
