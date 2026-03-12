package com.bloxbean.cardano.yaci.node.app.api.appmsg.dto;

import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.stream.Collectors;

public record AppBlockDto(
        @JsonProperty("blockNumber") long blockNumber,
        @JsonProperty("topicId") String topicId,
        @JsonProperty("messageCount") int messageCount,
        @JsonProperty("blockHash") String blockHash,
        @JsonProperty("prevBlockHash") String prevBlockHash,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("messageIds") List<String> messageIds
) {
    public static AppBlockDto from(AppBlock block) {
        return new AppBlockDto(
                block.getBlockNumber(),
                block.getTopicId(),
                block.messageCount(),
                block.blockHashHex(),
                block.prevBlockHashHex(),
                block.getTimestamp(),
                block.getMessages() != null
                        ? block.getMessages().stream()
                                .map(m -> m.getMessageIdHex())
                                .collect(Collectors.toList())
                        : List.of()
        );
    }
}
