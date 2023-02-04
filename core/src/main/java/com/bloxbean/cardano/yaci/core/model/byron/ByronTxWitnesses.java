package com.bloxbean.cardano.yaci.core.model.byron;

import com.bloxbean.cardano.yaci.core.model.byron.payload.ByronSscPayload;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = ByronSscPayload.TYPE)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ByronPkWitness.class, name = ByronPkWitness.TYPE),
    @JsonSubTypes.Type(value = ByronRedeemWitness.class, name = ByronRedeemWitness.TYPE),
    @JsonSubTypes.Type(value = ByronScriptWitness.class, name = ByronScriptWitness.TYPE),
    @JsonSubTypes.Type(value = ByronUnknownWitness.class, name = ByronUnknownWitness.TYPE)
})
public interface ByronTxWitnesses {

  String TYPE = "type";

  @JsonIgnore
  String getType();
}
