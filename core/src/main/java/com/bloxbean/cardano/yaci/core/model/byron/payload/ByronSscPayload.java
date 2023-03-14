package com.bloxbean.cardano.yaci.core.model.byron.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = ByronSscPayload.TYPE)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ByronCommitmentsPayload.class, name = ByronCommitmentsPayload.TYPE),
    @JsonSubTypes.Type(value = ByronOpeningsPayload.class, name = ByronOpeningsPayload.TYPE),
    @JsonSubTypes.Type(value = ByronSharesPayload.class, name = ByronSharesPayload.TYPE),
    @JsonSubTypes.Type(value = ByronCertificatesPayload.class, name = ByronCertificatesPayload.TYPE)
})
public interface ByronSscPayload {

  String TYPE = "type";

  @JsonIgnore
  String getType();
}
