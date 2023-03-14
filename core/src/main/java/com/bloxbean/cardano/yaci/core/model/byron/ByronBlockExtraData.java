package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronBlockExtraData<T> {

  private BlockVersion blockVersion;
  private SoftwareVersion softwareVersion;
  private T attributes;
  private String extraProof;
}
