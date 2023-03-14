package com.bloxbean.cardano.yaci.core.model.byron.payload;

import com.bloxbean.cardano.yaci.core.model.byron.ByronTx;
import com.bloxbean.cardano.yaci.core.model.byron.ByronTxWitnesses;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronTxPayload {

  private ByronTx transaction;
  private List<ByronTxWitnesses> witnesses;
}
