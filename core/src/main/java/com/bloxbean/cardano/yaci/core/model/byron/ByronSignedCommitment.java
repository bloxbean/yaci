package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronSignedCommitment {
  private String publicKey;
  private ByronCommitment commitment;
  private String signature;
}
