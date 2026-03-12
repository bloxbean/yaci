package com.bloxbean.cardano.yaci.node.app.api.appmsg.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AppMessageSubmitRequest(
        @JsonProperty("topicId") String topicId,
        @JsonProperty("messageBody") String messageBody
) {}
