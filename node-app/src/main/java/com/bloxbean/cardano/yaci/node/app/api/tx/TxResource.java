package com.bloxbean.cardano.yaci.node.app.api.tx;

import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.runtime.blockproducer.TransactionValidationException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/v1/tx")
@Produces(MediaType.APPLICATION_JSON)
public class TxResource {

    @Inject
    NodeAPI nodeAPI;

    /**
     * Blockfrost-compatible tx submission endpoint.
     * Accepts raw CBOR bytes (application/cbor) as used by BFBackendService.
     */
    @POST
    @Path("/submit")
    @Consumes("application/cbor")
    public Response submitCbor(byte[] txCbor) {
        return doSubmit(txCbor);
    }

    /**
     * Accepts hex-encoded transaction CBOR as plain text, as used by Yaci Store clients.
     */
    @POST
    @Path("/submit")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response submitHex(String txHex) {
        if (txHex == null || txHex.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Transaction hex string required"))
                    .build();
        }
        try {
            byte[] txCbor = hexToBytes(txHex.strip());
            return doSubmit(txCbor);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid hex string"))
                    .build();
        }
    }

    private Response doSubmit(byte[] txCbor) {
        if (txCbor == null || txCbor.length == 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Transaction CBOR bytes required"))
                    .build();
        }
        try {
            String txHash = nodeAPI.submitTransaction(txCbor);
            // Return quoted JSON string to match Blockfrost response format
            return Response.ok("\"" + txHash + "\"").build();
        } catch (TransactionValidationException e) {
            List<Map<String, String>> validationErrors = e.getErrors().stream()
                    .map(err -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("rule", err.rule());
                        m.put("message", err.message());
                        m.put("phase", err.phase());
                        return m;
                    })
                    .collect(Collectors.toList());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "Transaction validation failed");
            body.put("validationErrors", validationErrors);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(body)
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Failed to submit transaction: " + e.getMessage()))
                    .build();
        }
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
