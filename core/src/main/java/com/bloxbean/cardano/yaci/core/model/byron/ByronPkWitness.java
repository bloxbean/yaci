package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronPkWitness implements ByronTxWitnesses {

  public static final String TYPE = "ByronPkWitness";

  private String publicKey;
  private String signature;

  @Override
  public String getType() {
    return TYPE;
  }
}
