package com.bloxbean.cardano.yaci.core.model.byron.payload;

import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockVersion;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockVersionMod;
import com.bloxbean.cardano.yaci.core.model.byron.ByronUpdateData;
import com.bloxbean.cardano.yaci.core.model.byron.SoftwareVersion;
import lombok.*;

import java.util.Map;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder
public class ByronUpdateProposal {
  private ByronBlockVersion blockVersion;
  private ByronBlockVersionMod blockVersionMod;
  private SoftwareVersion softwareVersion;
  private Map<String, ByronUpdateData> data;
  private String attributes;
  private String from;
  private String signature;
}
