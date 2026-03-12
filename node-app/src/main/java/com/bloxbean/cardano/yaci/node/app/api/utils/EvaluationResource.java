package com.bloxbean.cardano.yaci.node.app.api.utils;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.ledgerrules.TransactionEvaluator;
import com.bloxbean.cardano.yaci.node.runtime.YaciNode;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionEvaluationService;
import io.quarkus.arc.ClientProxy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blockfrost/Ogmios-compatible Plutus script evaluation endpoint.
 * Returns computed ExUnits per redeemer for a given transaction.
 */
@Path("/api/v1/utils/txs")
@Produces(MediaType.APPLICATION_JSON)
public class EvaluationResource {

    private static final Logger log = LoggerFactory.getLogger(EvaluationResource.class);

    @Inject
    NodeAPI nodeAPI;

    /**
     * Accepts raw CBOR bytes (application/cbor).
     */
    @POST
    @Path("/evaluate")
    @Consumes("application/cbor")
    public Response evaluateCbor(byte[] txCbor) {
        return doEvaluate(txCbor);
    }

    /**
     * Accepts hex-encoded CBOR as plain text.
     */
    @POST
    @Path("/evaluate")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response evaluateHex(String txHex) {
        if (txHex == null || txHex.isBlank()) {
            return errorResponse("Transaction CBOR required");
        }
        try {
            byte[] txCbor = hexToBytes(txHex.strip());
            return doEvaluate(txCbor);
        } catch (IllegalArgumentException e) {
            return errorResponse("Invalid hex string");
        }
    }

    private Response doEvaluate(byte[] txCbor) {
        if (txCbor == null || txCbor.length == 0) {
            return errorResponse("Transaction CBOR bytes required");
        }

        YaciNode yaciNode = (YaciNode) ClientProxy.unwrap(nodeAPI);

        TransactionEvaluationService evalService = yaciNode.getTransactionEvalService();
        if (evalService == null) {
            return errorResponse("Script evaluation not initialized. " +
                    "Ensure tx-evaluation is enabled and protocol parameters are configured.");
        }

        try {
            List<TransactionEvaluator.EvaluationResult> results = evalService.evaluate(txCbor);

            // Build Ogmios-compatible response
            Map<String, Object> evaluationResult = new LinkedHashMap<>();
            for (TransactionEvaluator.EvaluationResult r : results) {
                String key = r.tag() + ":" + r.index();
                Map<String, Long> exUnits = new LinkedHashMap<>();
                exUnits.put("memory", r.memory());
                exUnits.put("steps", r.steps());
                evaluationResult.put(key, exUnits);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("EvaluationResult", evaluationResult);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "jsonwsp/response");
            response.put("version", "1.0");
            response.put("servicename", "ogmios");
            response.put("methodname", "EvaluateTx");
            response.put("result", result);

            return Response.ok(response).build();
        } catch (Exception e) {
            log.warn("Script evaluation failed: {}", e.getMessage());

            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("message", e.getMessage());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("EvaluationFailure", failure);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("type", "jsonwsp/response");
            response.put("version", "1.0");
            response.put("servicename", "ogmios");
            response.put("methodname", "EvaluateTx");
            response.put("result", result);

            return Response.ok(response).build();
        }
    }

    private Response errorResponse(String message) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("message", message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("EvaluationFailure", failure);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "jsonwsp/response");
        response.put("version", "1.0");
        response.put("servicename", "ogmios");
        response.put("methodname", "EvaluateTx");
        response.put("result", result);

        return Response.ok(response).build();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd-length hex string");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }
}
