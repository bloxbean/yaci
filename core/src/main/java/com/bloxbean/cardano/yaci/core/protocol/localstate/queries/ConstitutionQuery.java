package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.model.governance.Anchor;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.AnchorSerializer;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.List;

@Getter
@AllArgsConstructor
@ToString
// ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs
public class ConstitutionQuery implements EraQuery<ConstitutionQueryResult> {
    @NonNull
    private Era era;

    public ConstitutionQuery() {
        this.era = Era.Conway;
    }

    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array queryArray = new Array();
        queryArray.add(new UnsignedInteger(23));

        return wrapWithOuterArray(queryArray);
    }

    @Override
    public ConstitutionQueryResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<DataItem> dataItemList = ((Array) di[0]).getDataItems();

        int type = ((UnsignedInteger) dataItemList.get(0)).getValue().intValue(); //4

        List<DataItem> resultDIList = ((Array) dataItemList.get(1)).getDataItems();
        var items = (Array) resultDIList.get(0);

        Anchor anchor = AnchorSerializer.INSTANCE.deserializeDI(items.getDataItems().get(0));
        String script = null;

        if (items.getDataItems().get(1).getMajorType() == MajorType.BYTE_STRING) {
            script = HexUtil.encodeHexString(((ByteString) items.getDataItems().get(1)).getBytes());
        }

        return new ConstitutionQueryResult(anchor, script);
    }
}
