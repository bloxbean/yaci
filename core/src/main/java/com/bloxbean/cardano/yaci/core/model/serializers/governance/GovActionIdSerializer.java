package com.bloxbean.cardano.yaci.core.model.serializers.governance;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.transaction.raw.RawGovActionId;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.client.util.StringUtils;
import com.bloxbean.cardano.yaci.core.model.governance.GovActionId;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public enum GovActionIdSerializer implements Serializer<GovActionId> {
    INSTANCE;

    @Override
    public DataItem serializeDI(GovActionId govActionId) {
        Array array = new Array();

        if (StringUtils.isEmpty(govActionId.getTransactionId()) || govActionId.getGov_action_index() == null) {
            log.error("Invalid gov_action_id : {}", govActionId);
            throw new IllegalArgumentException("Invalid gov_action_id, " +
                    "expect transaction_id and gov_action_index not null and not empty");
        }

        array.add(new ByteString(HexUtil.decodeHexString(govActionId.getTransactionId())));
        array.add(new UnsignedInteger(govActionId.getGov_action_index()));

        return array;
    }

    @Override
    public GovActionId deserializeDI(DataItem di) {
        return read(RawGovActionId.lenient(RawModels.span(di)));
    }

    /**
     * @param id a governance action id as received
     * @return the id
     */
    public GovActionId read(RawGovActionId id) {
        return new GovActionId(HexUtil.encodeHexString(id.transactionId()), id.index());
    }

}
