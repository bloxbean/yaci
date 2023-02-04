package com.bloxbean.cardano.yaci.core.model.byron;

import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronDlgPayload;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronSscPayload;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronTxPayload;
import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronUpdatePayload;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronBlockBody {
    //Using the same class. But most of the fields are not applicable for Byron
   // private List<ByronTx> transactionBodies;
    private List<ByronTxPayload> txPayload;
    private ByronSscPayload sscPayload;
    private List<ByronDlgPayload> dlgPayload;
    private ByronUpdatePayload updPayload;
}
