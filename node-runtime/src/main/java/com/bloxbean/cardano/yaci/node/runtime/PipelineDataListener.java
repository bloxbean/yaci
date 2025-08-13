package com.bloxbean.cardano.yaci.node.runtime;

import com.bloxbean.cardano.yaci.core.exception.BlockParseRuntimeException;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.BlockHeader;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.byron.ByronBlockHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbHead;
import com.bloxbean.cardano.yaci.core.model.byron.ByronEbBlock;
import com.bloxbean.cardano.yaci.core.model.byron.ByronMainBlock;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Tip;
import com.bloxbean.cardano.yaci.helper.listener.BlockChainDataListener;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * PipelineDataListener is an adapter that implements BlockChainDataListener
 * and delegates events to the appropriate pipeline managers:
 * - HeaderSyncManager for ChainSync events (headers)
 * - BodyFetchManager for BlockFetch events (bodies)
 * - YaciNode for rollback coordination
 * 
 * This allows the pipeline architecture to work with the existing
 * PeerClient.connect() method without modifications.
 */
@Slf4j
public class PipelineDataListener implements BlockChainDataListener {
    
    private final HeaderSyncManager headerSyncManager;
    private final BodyFetchManager bodyFetchManager;
    private final YaciNode yaciNode;
    
    /**
     * Create a new PipelineDataListener
     * 
     * @param headerSyncManager Manager for header synchronization
     * @param bodyFetchManager Manager for body fetching
     * @param yaciNode Reference to YaciNode for rollback coordination
     */
    public PipelineDataListener(HeaderSyncManager headerSyncManager,
                               BodyFetchManager bodyFetchManager,
                               YaciNode yaciNode) {
        this.headerSyncManager = headerSyncManager;
        this.bodyFetchManager = bodyFetchManager;
        this.yaciNode = yaciNode;
        
        log.info("PipelineDataListener initialized for parallel header/body processing");
    }
    
    // ================================================================
    // ChainSync Events - Delegate to HeaderSyncManager
    // ================================================================
    
    @Override
    public void rollforward(Tip tip, BlockHeader blockHeader, byte[] originalHeaderBytes) {
        // Delegate header processing to HeaderSyncManager
        headerSyncManager.rollforward(tip, blockHeader, originalHeaderBytes);
        
        // Resume BodyFetchManager if paused and headers are flowing after intersection
        yaciNode.resumeBodyFetchOnHeaderFlow();
    }
    
    @Override
    public void rollforwardByronEra(Tip tip, ByronBlockHead byronBlockHead, byte[] originalHeaderBytes) {
        // Delegate Byron header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronBlockHead, originalHeaderBytes);
        
        // Resume BodyFetchManager if paused and headers are flowing after intersection
        yaciNode.resumeBodyFetchOnHeaderFlow();
    }
    
    @Override
    public void rollforwardByronEra(Tip tip, ByronEbHead byronEbHead, byte[] originalHeaderBytes) {
        // Delegate Byron EB header processing to HeaderSyncManager
        headerSyncManager.rollforwardByronEra(tip, byronEbHead, originalHeaderBytes);
        
        // Resume BodyFetchManager if paused and headers are flowing after intersection
        yaciNode.resumeBodyFetchOnHeaderFlow();
    }
    
    // ================================================================
    // BlockFetch Events - Delegate to BodyFetchManager
    // ================================================================
    
    @Override
    public void onBlock(Era era, Block block, List<Transaction> transactions) {
        // Delegate block body processing to BodyFetchManager
        bodyFetchManager.onBlock(era, block, transactions);
        
        // Update sync progress tracking in YaciNode
        yaciNode.updateSyncProgress();
        
        // Notify server about new block availability (only during STEADY_STATE)
        yaciNode.notifyServerNewBlockStored();
    }
    
    @Override
    public void onByronBlock(ByronMainBlock byronBlock) {
        // Delegate Byron block processing to BodyFetchManager
        bodyFetchManager.onByronBlock(byronBlock);
        
        // Update sync progress tracking in YaciNode
        yaciNode.updateSyncProgress();
        
        // Notify server about new block availability (only during STEADY_STATE)
        yaciNode.notifyServerNewBlockStored();
    }
    
    @Override
    public void onByronEbBlock(ByronEbBlock byronEbBlock) {
        // Delegate Byron EB block processing to BodyFetchManager
        bodyFetchManager.onByronEbBlock(byronEbBlock);
        
        // Update sync progress tracking in YaciNode
        yaciNode.updateSyncProgress();
        
        // Notify server about new block availability (only during STEADY_STATE)
        yaciNode.notifyServerNewBlockStored();
    }
    
    @Override
    public void batchStarted() {
        // Delegate batch start to BodyFetchManager
        bodyFetchManager.batchStarted();
    }
    
    @Override
    public void batchDone() {
        // Delegate batch completion to BodyFetchManager
        bodyFetchManager.batchDone();
    }
    
    @Override
    public void noBlockFound(Point from, Point to) {
        // Delegate no block found event to BodyFetchManager
        bodyFetchManager.noBlockFound(from, to);
    }
    
    // ================================================================
    // Control Events - Coordinate Between Components
    // ================================================================
    
    @Override
    public void intersactFound(Tip tip, Point point) {
        // Notify HeaderSyncManager about intersection
        headerSyncManager.intersactFound(tip, point);
        
        // Update sync phase in YaciNode for rollback classification
        yaciNode.onIntersectionFound();
        
        log.info("Intersection found at point: {} - notified both header manager and YaciNode", point);
    }
    
    @Override
    public void intersactNotFound(Tip tip) {
        // Notify HeaderSyncManager about intersection not found
        headerSyncManager.intersactNotFound(tip);
        
        log.warn("Intersection not found for tip: {} - notified header manager", tip);
    }
    
    @Override
    public void onRollback(Point point) {
        // Delegate rollback handling to YaciNode for classification and coordination
        // YaciNode will pause/resume BodyFetchManager and handle server notifications
        yaciNode.handleRollback(point);
        
        log.info("Rollback to point: {} - delegated to YaciNode for coordination", point);
    }
    
    @Override
    public void onDisconnect() {
        // Notify both managers about disconnection
        headerSyncManager.onDisconnect();
        bodyFetchManager.onDisconnect();
        
        log.info("Disconnection event - notified both header and body managers");
    }
    
    @Override
    public void onParsingError(BlockParseRuntimeException e) {
        // Delegate parsing errors to BodyFetchManager
        bodyFetchManager.onParsingError(e);
        
        log.error("Block parsing error delegated to BodyFetchManager", e);
    }
}