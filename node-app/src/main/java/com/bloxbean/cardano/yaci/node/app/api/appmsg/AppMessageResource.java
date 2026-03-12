package com.bloxbean.cardano.yaci.node.app.api.appmsg;

import com.bloxbean.cardano.yaci.core.protocol.appmsg.model.AppMessage;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.ledger.AppBlock;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedger;
import com.bloxbean.cardano.yaci.node.api.ledger.AppLedgerTip;
import com.bloxbean.cardano.yaci.node.app.api.appmsg.dto.AppBlockDto;
import com.bloxbean.cardano.yaci.node.app.api.appmsg.dto.AppMessageSubmitRequest;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.AppMessageMemPool;
import com.bloxbean.cardano.yaci.node.runtime.appmsg.YaciAppMessageHandler;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/v1/appmsg")
@Produces(MediaType.APPLICATION_JSON)
public class AppMessageResource {

    @Inject
    NodeAPI nodeAPI;

    @POST
    @Path("/submit")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(AppMessageSubmitRequest request) {
        if (request == null || request.topicId() == null || request.topicId().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "topicId is required"))
                    .build();
        }
        if (request.messageBody() == null || request.messageBody().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "messageBody is required"))
                    .build();
        }

        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        YaciAppMessageHandler handler = yaciNode.getAppMessageHandler();
        if (handler == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "App-layer messaging is not enabled"))
                    .build();
        }

        byte[] bodyBytes = request.messageBody().getBytes(StandardCharsets.UTF_8);
        byte[] messageId = computeMessageId(request.topicId(), bodyBytes);
        long defaultTtl = 600; // 10 minutes
        long expiresAt = (System.currentTimeMillis() / 1000) + defaultTtl;

        AppMessage message = AppMessage.builder()
                .messageId(messageId)
                .messageBody(bodyBytes)
                .topicId(request.topicId())
                .authMethod(0)
                .authProof(new byte[0])
                .expiresAt(expiresAt)
                .build();

        boolean accepted = handler.handleLocalSubmission(message);
        if (accepted) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messageId", HexUtil.encodeHexString(messageId));
            result.put("topicId", request.topicId());
            result.put("status", "accepted");
            return Response.status(Response.Status.ACCEPTED).entity(result).build();
        } else {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Message rejected (duplicate or auth failure)"))
                    .build();
        }
    }

    @GET
    @Path("/mempool")
    public Response getMempool() {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        AppMessageMemPool memPool = yaciNode.getAppMessageMemPool();
        if (memPool == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "App-layer messaging is not enabled"))
                    .build();
        }

        List<AppMessage> messages = memPool.getMessages(100);
        List<Map<String, Object>> messageList = messages.stream()
                .map(m -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("messageId", m.getMessageIdHex());
                    entry.put("topicId", m.getTopicId());
                    entry.put("size", m.getSize());
                    return entry;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", memPool.size());
        result.put("messages", messageList);
        return Response.ok(result).build();
    }

    @GET
    @Path("/blocks/{topic}")
    public Response getBlocks(@PathParam("topic") String topic,
                              @QueryParam("from") @DefaultValue("0") long from,
                              @QueryParam("limit") @DefaultValue("20") int limit) {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        AppLedger ledger = yaciNode.getAppLedger();
        if (ledger == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "App ledger is not enabled"))
                    .build();
        }

        long toBlock = from + limit - 1;
        List<AppBlock> blocks = ledger.getBlocks(topic, from, toBlock);
        List<AppBlockDto> dtos = blocks.stream()
                .map(AppBlockDto::from)
                .collect(Collectors.toList());

        Optional<AppLedgerTip> tip = ledger.getTip(topic);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topic", topic);
        result.put("tipBlockNumber", tip.map(AppLedgerTip::getBlockNumber).orElse(-1L));
        result.put("blocks", dtos);
        return Response.ok(result).build();
    }

    @GET
    @Path("/blocks/{topic}/{blockNumber}")
    public Response getBlock(@PathParam("topic") String topic,
                             @PathParam("blockNumber") long blockNumber) {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        AppLedger ledger = yaciNode.getAppLedger();
        if (ledger == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "App ledger is not enabled"))
                    .build();
        }

        Optional<AppBlock> block = ledger.getBlock(topic, blockNumber);
        if (block.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Block not found"))
                    .build();
        }

        return Response.ok(AppBlockDto.from(block.get())).build();
    }

    @GET
    @Path("/status")
    public Response getStatus() {
        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);
        YaciAppMessageHandler handler = yaciNode.getAppMessageHandler();
        AppMessageMemPool memPool = yaciNode.getAppMessageMemPool();
        AppLedger ledger = yaciNode.getAppLedger();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appLayerEnabled", handler != null);

        if (handler != null) {
            result.put("messagesReceived", handler.getMessagesReceived());
            result.put("messagesAccepted", handler.getMessagesAccepted());
            result.put("messagesRejected", handler.getMessagesRejected());
        }

        if (memPool != null) {
            result.put("mempoolSize", memPool.size());
        }

        if (ledger != null) {
            Map<String, Object> topics = new LinkedHashMap<>();
            var yaciConfig = yaciNode.getConfig();
            if (yaciConfig != null && yaciConfig.getAppTopics() != null) {
                for (String topic : yaciConfig.getAppTopics()) {
                    Optional<AppLedgerTip> tip = ledger.getTip(topic);
                    Map<String, Object> topicInfo = new LinkedHashMap<>();
                    topicInfo.put("tipBlockNumber", tip.map(AppLedgerTip::getBlockNumber).orElse(-1L));
                    topicInfo.put("totalMessages", tip.map(AppLedgerTip::getTotalMessages).orElse(0L));
                    topics.put(topic, topicInfo);
                }
            }
            result.put("topics", topics);
        }

        return Response.ok(result).build();
    }

    private static byte[] computeMessageId(String topicId, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(topicId.getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            // Include a nonce for uniqueness
            digest.update(Long.toString(System.nanoTime()).getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
