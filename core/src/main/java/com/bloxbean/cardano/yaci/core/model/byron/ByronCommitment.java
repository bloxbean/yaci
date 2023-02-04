package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronCommitment {
  private Map<String, String> map;
  private ByronSecretProof vssProof;
}
