package com.bloxbean.cardano.yaci.core.model.byron;

import com.bloxbean.cardano.client.crypto.Base58;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronAddress {
  private String base58Raw;
  private String addressId;
  private ByronAddressAttr addressAttr;
  private String addressType;

  @JsonIgnore
  public byte[] getBytes() {
    return Base58.decode(base58Raw);
  }
}
