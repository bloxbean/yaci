package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.yaci.core.model.governance.Drep;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.DRepStake;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toInt;

@Getter
@ToString
@AllArgsConstructor
@Slf4j
public class DRepStakeDistributionQuery implements EraQuery<DRepStakeDistributionQueryResult> {
    private Era era;
    private List<DRep> dReps;

    public DRepStakeDistributionQuery(List<DRep> dReps) {
        this(Era.Conway, dReps);
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(26));

        Array dRepArray = new Array();
        dReps.forEach(dRep -> dRepArray.add(dRep.serialize()));

        array.add(dRepArray);

        return wrapWithOuterArray(array);
    }

    @Override
    public DRepStakeDistributionQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        DRepStakeDistributionQueryResult result = new DRepStakeDistributionQueryResult();

        List<DataItem> dataItemList = extractResultArray(di[0]);
        Map dRepStakeMap = (Map) dataItemList.get(0);

        for (var key : dRepStakeMap.getKeys()) {
            var dRepDI = (Array) key;
            int dRepType = toInt(dRepDI.getDataItems().get(0));

            var votingPower = ((UnsignedInteger) dRepStakeMap.get(key)).getValue();
            Drep dRep = null;
            if (dRepType == 0 || dRepType == 1) {
                String hash = HexUtil.encodeHexString(((ByteString) dRepDI.getDataItems().get(1)).getBytes());
                dRep = dRepType == 0 ? Drep.addrKeyHash(hash) : Drep.scriptHash(hash);
            } else if (dRepType == 2) {
                dRep = Drep.abstain();
            } else if (dRepType == 3) {
                dRep = Drep.noConfidence();
            }

            DRepStake dRepStake = DRepStake.builder()
                    .dRep(dRep)
                    .amount(votingPower)
                    .build();

            result.addDRepStake(dRepStake);
        }

        return result;
    }
}
