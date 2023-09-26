package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.List;

@Slf4j
public enum ProposalProcedureSerializer implements Serializer<ProposalProcedure> {
    INSTANCE;

    /**
     * proposal_procedure =
     *   [ deposit : coin
     *   , reward_account
     *   , gov_action
     *   , anchor
     *   ]
     * @param di
     * @return
     */
    @Override
    public ProposalProcedure deserializeDI(DataItem di) {
        log.trace("ProposalProcedureSerializer.deserializeDI ------");

        Array proposalProcedureArray = (Array) di;
        List<DataItem> diList = proposalProcedureArray.getDataItems();

        BigInteger deposit = CborSerializationUtil.toBigInteger(diList.get(0));
        String rewardAccount = CborSerializationUtil.toHex(diList.get(1));
        GovAction govAction = GovActionSerializer.INSTANCE.deserializeDI(diList.get(2));
        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(diList.get(3));

        return new ProposalProcedure(deposit, rewardAccount, govAction, anchor);
    }
}
