package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronSecretProof {
  private String extraGen;
  private String proof;
  private String parallelProofs;
  private List<String> commitments;
}
