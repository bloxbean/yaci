package com.bloxbean.cardano.yaci.core.model.byron.payload;

import com.bloxbean.cardano.yaci.core.model.byron.ByronSignedCommitment;
import com.bloxbean.cardano.yaci.core.model.byron.ByronSscCert;
import lombok.*;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronCommitmentsPayload implements ByronSscPayload {

  public static final String TYPE = "ByronCommitmentsPayload";

  private List<ByronSignedCommitment> sscCommitments;
  private List<ByronSscCert> sscCerts;

  @Override
  public String getType() {
    return TYPE;
  }
}
