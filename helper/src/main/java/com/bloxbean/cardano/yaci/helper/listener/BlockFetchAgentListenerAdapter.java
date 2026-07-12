package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.TxCborUtil;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.Utxo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
public class BlockFetchAgentListenerAdapter implements BlockfetchAgentListener {
    private BlockChainDataListener blockChainDataListener;

    public BlockFetchAgentListenerAdapter(BlockChainDataListener blockChainDataListener) {
        this.blockChainDataListener = blockChainDataListener;
    }

    @Override
    public void blockFound(Block block) {
        List<Transaction> transactionEvents = new ArrayList<>();
        boolean canAssembleFullTxCbor = canAssembleFullTxCbor(block);
        int i = 0;
        for (TransactionBody txBody: block.getTransactionBodies()) {
            Witnesses witnesses = getWitnesses(block, i);
            AuxData auxData = getAuxData(block, i);

            boolean invalidTxn = false;
            if (block.getInvalidTransactions() != null && block.getInvalidTransactions().size() > 0) {
                if (block.getInvalidTransactions().contains(Integer.valueOf(i)))
                    invalidTxn = true;
            }

            List<Utxo> utxos;
            if (!invalidTxn)
                utxos = getUtxosFromOutput(txBody);
            else
                utxos = Collections.emptyList();

            Optional<Utxo> collateralReturnUtxo = getCollateralReturnUtxo(txBody);
            String txCbor = canAssembleFullTxCbor
                    ? assembleTxCbor(block, i, txBody, witnesses, auxData, !invalidTxn)
                    : null;

            Transaction transactionEvent = Transaction.builder()
                    .blockNumber(block.getHeader().getHeaderBody().getBlockNumber())
                    .slot(block.getHeader().getHeaderBody().getSlot())
                    .txHash(txBody.getTxHash())
                    .txCbor(txCbor)
                    .body(txBody)
                    .utxos(utxos)
                    .collateralReturnUtxo(collateralReturnUtxo.orElse(null))
                    .witnesses(witnesses)
                    .auxData(auxData)
                    .invalid(invalidTxn)
                    .build();

            transactionEvents.add(transactionEvent);
            i++;
        }


        blockChainDataListener.onBlock(block.getEra(), block, transactionEvents);
    }

    private Witnesses getWitnesses(Block block, int txIndex) {
        List<Witnesses> transactionWitness = block.getTransactionWitness();
        if (transactionWitness == null || txIndex >= transactionWitness.size()) {
            return null;
        }

        return transactionWitness.get(txIndex);
    }

    private AuxData getAuxData(Block block, int txIndex) {
        if (block.getAuxiliaryDataMap() == null) {
            return null;
        }

        return block.getAuxiliaryDataMap().get(txIndex);
    }

    private boolean canAssembleFullTxCbor(Block block) {
        if (!YaciConfig.INSTANCE.isReturnFullTxCbor()) {
            return false;
        }

        List<TransactionBody> transactionBodies = block.getTransactionBodies();
        List<Witnesses> transactionWitness = block.getTransactionWitness();
        if (transactionBodies == null || transactionWitness == null
                || transactionBodies.size() != transactionWitness.size()) {
            log.error("block: {} segment count mismatch. full transaction cbor will not be available. bodies: {}, witnesses: {}",
                    block.getHeader().getHeaderBody().getBlockNumber(),
                    transactionBodies == null ? null : transactionBodies.size(),
                    transactionWitness == null ? null : transactionWitness.size());
            return false;
        }

        for (int i = 0; i < transactionBodies.size(); i++) {
            // Raw witness-count mismatches leave cbor unset in BlockSerializer.setWitnessCbor,
            // so this check enforces the raw-count invariant without re-reading the raw lists here.
            TransactionBody txBody = transactionBodies.get(i);
            Witnesses witnesses = transactionWitness.get(i);
            if (txBody == null || txBody.getCbor() == null || witnesses == null || witnesses.getCbor() == null) {
                log.error("block: {} missing required raw segment. full transaction cbor will not be available",
                        block.getHeader().getHeaderBody().getBlockNumber());
                return false;
            }
        }

        return true;
    }

    private String assembleTxCbor(Block block, int txIndex, TransactionBody txBody, Witnesses witnesses,
                                  AuxData auxData, boolean isValid) {
        byte[] auxBytes = null;
        boolean parsedAuxExists = block.getAuxiliaryDataMap() != null && block.getAuxiliaryDataMap().containsKey(txIndex);
        boolean bodySaysAuxExists = txBody.getAuxiliaryDataHash() != null;
        if (parsedAuxExists || bodySaysAuxExists) {
            if (auxData == null || auxData.getCbor() == null) {
                log.debug("block: {} tx index: {} missing raw auxiliary data bytes. full transaction cbor is null",
                        block.getHeader().getHeaderBody().getBlockNumber(), txIndex);
                return null;
            }
            auxBytes = HexUtil.decodeHexString(auxData.getCbor());
        }

        byte[] fullTxCbor = TxCborUtil.assembleTxCbor(block.getEra(),
                HexUtil.decodeHexString(txBody.getCbor()),
                HexUtil.decodeHexString(witnesses.getCbor()),
                auxBytes,
                isValid);
        return HexUtil.encodeHexString(fullTxCbor);
    }

    private List<Utxo> getUtxosFromOutput(TransactionBody txBody) {
        List<Utxo> utxos = new ArrayList<>();
        for (int index = 0; index < txBody.getOutputs().size(); index++) {
            TransactionOutput txOutput = txBody.getOutputs().get(index);
            Utxo utxo = Utxo.builder()
                    .txHash(txBody.getTxHash())
                    .index(index)
                    .address(txOutput.getAddress())
                    .amounts(txOutput.getAmounts())
                    .datumHash(txOutput.getDatumHash())
                    .inlineDatum(txOutput.getInlineDatum())
                    .scriptRef(txOutput.getScriptRef())
                    .build();

            utxos.add(utxo);
        }

        return utxos;
    }

    private Optional<Utxo> getCollateralReturnUtxo(TransactionBody txBody) {
        //Check if collateral return output is there
        if (txBody.getCollateralReturn() != null) {
            TransactionOutput txOutput = txBody.getCollateralReturn();
            Utxo utxo = Utxo.builder()
                    .txHash(txBody.getTxHash())
                    .index(txBody.getOutputs().size())
                    .address(txOutput.getAddress())
                    .amounts(txOutput.getAmounts())
                    .datumHash(txOutput.getDatumHash())
                    .inlineDatum(txOutput.getInlineDatum())
                    .scriptRef(txOutput.getScriptRef())
                    .build();

            return Optional.of(utxo);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public void byronBlockFound(ByronMainBlock byronBlock) {
        blockChainDataListener.onByronBlock(byronBlock);
    }

    @Override
    public void byronEbBlockFound(ByronEbBlock byronEbBlock) {
        blockChainDataListener.onByronEbBlock(byronEbBlock);
    }

    @Override
    public void batchStarted() {
        blockChainDataListener.batchStarted();
    }

    @Override
    public void batchDone() {
        blockChainDataListener.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        blockChainDataListener.noBlockFound(from, to);
    }

    @Override
    public void onDisconnect() {
        blockChainDataListener.onDisconnect();
    }

    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        blockChainDataListener.onParsingError(e);
    }
}
