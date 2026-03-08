package com.bloxbean.cardano.yaci.node.app.api.tx;

import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.serializers.BlockSerializer;
import com.bloxbean.cardano.yaci.core.storage.ChainState;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.node.api.NodeAPI;
import com.bloxbean.cardano.yaci.node.api.utxo.UtxoState;
import com.bloxbean.cardano.yaci.node.api.utxo.model.AssetAmount;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Outpoint;
import com.bloxbean.cardano.yaci.node.api.utxo.model.Utxo;
import com.bloxbean.cardano.yaci.node.app.api.tx.dto.TxAmountDto;
import com.bloxbean.cardano.yaci.node.app.api.tx.dto.TxDto;
import com.bloxbean.cardano.yaci.node.app.api.tx.dto.TxInputsOutputsDto;
import com.bloxbean.cardano.yaci.node.app.api.tx.dto.TxUtxoDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/api/v1/txs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TransactionResource {
    private static final Logger log = LoggerFactory.getLogger(TransactionResource.class);

    @Inject
    NodeAPI nodeAPI;

    @GET
    @Path("/{txHash}")
    public Response getTxInfo(@PathParam("txHash") String txHash) {
        UtxoState utxoState = nodeAPI.getUtxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "UTXO state disabled"))
                    .build();
        }

        List<Utxo> outputs = utxoState.getOutputsByTxHash(txHash);
        if (outputs.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Transaction not found",
                            "status_code", 404,
                            "message", "The requested component has not been found."))
                    .build();
        }

        try {
            long blockNumber = outputs.get(0).blockNumber();
            ChainState chainState = nodeAPI.getChainState();
            byte[] blockCbor = chainState != null ? chainState.getBlockByNumber(blockNumber) : null;
            if (blockCbor == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Block not found for transaction"))
                        .build();
            }

            Block block = BlockSerializer.INSTANCE.deserialize(blockCbor);
            if (block == null || block.getTransactionBodies() == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Block deserialization failed"))
                        .build();
            }

            // Find the tx in the block
            TransactionBody targetTx = null;
            int txIndex = 0;
            for (int i = 0; i < block.getTransactionBodies().size(); i++) {
                TransactionBody txBody = block.getTransactionBodies().get(i);
                if (txHash.equals(txBody.getTxHash())) {
                    targetTx = txBody;
                    txIndex = i;
                    break;
                }
            }

            if (targetTx == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Transaction not found in block"))
                        .build();
            }

            // Compute output amounts
            List<TxAmountDto> outputAmounts = new ArrayList<>();
            java.math.BigInteger totalLovelace = java.math.BigInteger.ZERO;
            if (targetTx.getOutputs() != null) {
                for (var out : targetTx.getOutputs()) {
                    if (out.getAmounts() != null) {
                        for (var amt : out.getAmounts()) {
                            if ("lovelace".equals(amt.getUnit()) && amt.getQuantity() != null) {
                                totalLovelace = totalLovelace.add(amt.getQuantity());
                            }
                        }
                    }
                }
            }
            outputAmounts.add(new TxAmountDto("lovelace", totalLovelace.toString()));

            // Compute block time from genesis
            long slot = block.getHeader().getHeaderBody().getSlot();
            String blockHash = block.getHeader().getHeaderBody().getBlockHash();

            // Fee
            String fees = targetTx.getFee() != null ? targetTx.getFee().toString() : "0";

            // Validity interval
            String invalidBefore = targetTx.getValidityIntervalStart() > 0
                    ? String.valueOf(targetTx.getValidityIntervalStart()) : null;
            String invalidHereafter = targetTx.getTtl() > 0
                    ? String.valueOf(targetTx.getTtl()) : null;

            // UTXO count: inputs + outputs
            int inputCount = targetTx.getInputs() != null ? targetTx.getInputs().size() : 0;
            int outputCount = targetTx.getOutputs() != null ? targetTx.getOutputs().size() : 0;

            TxDto dto = new TxDto(
                    txHash,
                    blockHash,
                    blockNumber,
                    0L, // block_time — not easily available without genesis timestamp
                    slot,
                    txIndex,
                    outputAmounts,
                    fees,
                    0, // size — not tracked in deserialized block
                    invalidBefore,
                    invalidHereafter,
                    inputCount + outputCount,
                    true // valid_contract — if it's in a block, it passed validation
            );
            return Response.ok(dto).build();
        } catch (Exception e) {
            log.warn("Failed to build tx info for {}: {}", txHash, e.getMessage());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Failed to retrieve transaction info"))
                    .build();
        }
    }

    @GET
    @Path("/{txHash}/utxos")
    public Response getTxUtxos(@PathParam("txHash") String txHash) {
        UtxoState utxoState = nodeAPI.getUtxoState();
        if (utxoState == null || !utxoState.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "UTXO state disabled"))
                    .build();
        }

        // Get all outputs for this tx (both spent and unspent)
        List<Utxo> outputs = utxoState.getOutputsByTxHash(txHash);
        if (outputs.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Transaction not found"))
                    .build();
        }

        List<TxUtxoDto> outputDtos = outputs.stream().map(this::toTxUtxoDto).toList();

        // Resolve inputs: get blockNumber from first output, load block, find tx, extract inputs
        List<TxUtxoDto> inputDtos = resolveInputs(txHash, outputs.get(0).blockNumber(), utxoState);

        return Response.ok(new TxInputsOutputsDto(txHash, inputDtos, outputDtos)).build();
    }

    private List<TxUtxoDto> resolveInputs(String txHash, long blockNumber, UtxoState utxoState) {
        List<TxUtxoDto> inputDtos = new ArrayList<>();
        try {
            ChainState chainState = nodeAPI.getChainState();
            if (chainState == null) return inputDtos;

            byte[] blockCbor = chainState.getBlockByNumber(blockNumber);
            if (blockCbor == null) return inputDtos;

            Block block = BlockSerializer.INSTANCE.deserialize(blockCbor);
            if (block == null || block.getTransactionBodies() == null) return inputDtos;

            // Find the transaction in the block
            TransactionBody targetTx = null;
            for (TransactionBody txBody : block.getTransactionBodies()) {
                if (txHash.equals(txBody.getTxHash())) {
                    targetTx = txBody;
                    break;
                }
            }
            if (targetTx == null || targetTx.getInputs() == null) return inputDtos;

            // Look up each input UTXO
            for (TransactionInput input : targetTx.getInputs()) {
                Outpoint outpoint = new Outpoint(input.getTransactionId(), input.getIndex());
                utxoState.getUtxoSpentOrUnspent(outpoint)
                        .ifPresent(utxo -> inputDtos.add(toTxUtxoDto(utxo)));
            }
        } catch (Exception e) {
            log.warn("Failed to resolve inputs for tx {}: {}", txHash, e.getMessage());
        }
        return inputDtos;
    }

    private TxUtxoDto toTxUtxoDto(Utxo utxo) {
        List<TxAmountDto> amounts = new ArrayList<>();
        amounts.add(new TxAmountDto("lovelace", utxo.lovelace().toString()));
        if (utxo.assets() != null) {
            for (AssetAmount a : utxo.assets()) {
                String unit = a.policyId() + a.assetName();
                amounts.add(new TxAmountDto(unit, a.quantity().toString()));
            }
        }
        String inlineHex = utxo.inlineDatum() != null ? HexUtil.encodeHexString(utxo.inlineDatum()) : null;
        return new TxUtxoDto(
                utxo.outpoint().txHash(),
                utxo.outpoint().index(),
                utxo.address(),
                amounts,
                utxo.datumHash(),
                inlineHex,
                utxo.referenceScriptHash()
        );
    }
}
