package com.bloxbean.cardano.yaci.core.model.byron.payload;

import com.bloxbean.cardano.yaci.core.model.byron.ByronSscCert;
import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronOpeningsPayload implements ByronSscPayload {

  public static final String TYPE = "ByronOpeningsPayload";

  private Map<String, String> sscOpens;
  private List<ByronSscCert> sscCerts;

  @Override
  public String getType() {
    return TYPE;
  }
}
