package com.bloxbean.cardano.yaci.helper.listener;

import com.bloxbean.cardano.yaci.core.model.*;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.blockfetch.BlockfetchAgentListener;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.Utxo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class BlockFetchAgentListenerAdapter implements BlockfetchAgentListener {
    private BlockChainDataListener blockChainDataListener;

    public BlockFetchAgentListenerAdapter(BlockChainDataListener blockChainDataListener) {
        this.blockChainDataListener = blockChainDataListener;
    }

    @Override
    public void blockFound(Block block) {
        List<Transaction> transactionEvents = new ArrayList<>();
        int i = 0;
        for (TransactionBody txBody: block.getTransactionBodies()) {
            Witnesses witnesses = block.getTransactionWitness().get(i);
            AuxData auxData = block.getAuxiliaryDataMap().get(i);

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

            Transaction transactionEvent = Transaction.builder()
                    .blockNumber(block.getHeader().getHeaderBody().getBlockNumber())
                    .slot(block.getHeader().getHeaderBody().getSlot())
                    .txHash(txBody.getTxHash())
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
    public void batchDone() {
        blockChainDataListener.batchDone();
    }

    @Override
    public void noBlockFound(Point from, Point to) {
        blockChainDataListener.noBlockFound(from, to);
    }
}
