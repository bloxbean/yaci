package com.bloxbean.cardano.yaci.core.model.byron;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronSscCert {
  private String vssPublicKey;
  private long expiryEpoch;
  private String signature;
  private String publicKey;
}
