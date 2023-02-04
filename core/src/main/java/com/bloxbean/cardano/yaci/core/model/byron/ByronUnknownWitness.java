package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronUnknownWitness implements ByronTxWitnesses {

  public static final String TYPE = "ByronUnknownWitness";

  private String data;

  @Override
  public String getType() {
    return TYPE;
  }
}
