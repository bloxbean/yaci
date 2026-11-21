package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toBigInteger;
import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.toHex;

/**
 * A pool stake distribution, as GetStakeDistribution(2) and GetPoolDistr(2) answer it. Up to N2C V20 the node
 * answers {@code {pool => [stake, vrf]}}. From V21 (ShelleyNodeToClientVersion13, ouroboros-consensus 3.0.1.0
 * Shelley/Ledger/Query.hs) the old queries are unsupported and the new ones answer the ledger's PoolDistr,
 * {@code [{pool => [stake, total pool stake, vrf, ? bls]}, total active stake]}.
 */
final class PoolDistributions {
    private PoolDistributions() {
    }

    /** Whether the negotiated version uses GetStakeDistribution2 and GetPoolDistr2 and their result. */
    static boolean ledgerForm(AcceptVersion protocolVersion) {
        return protocolVersion.getVersionNumber() >= N2CVersionTableConstant.PROTOCOL_V21;
    }

    record Distribution(java.util.Map<String, IndividualPoolStake> pools, BigInteger totalActiveStake) {
    }

    /**
     * Reads the result, keyed by pool id as the node's map is. (StakeDistributionQuery used to key it by VRF key
     * hash, which lost every pool sharing its VRF key with another: 21 of 517 on preprod.)
     */
    static Distribution read(AcceptVersion protocolVersion, DataItem result) {
        boolean ledgerForm = ledgerForm(protocolVersion);
        Map distr;
        BigInteger totalActiveStake = null;
        if (ledgerForm) {
            List<DataItem> poolDistr = ((Array) result).getDataItems();
            distr = (Map) poolDistr.get(0);
            totalActiveStake = toBigInteger(poolDistr.get(1));
        } else {
            distr = (Map) result;
        }

        java.util.Map<String, IndividualPoolStake> pools = new LinkedHashMap<>();
        for (DataItem key : distr.getKeys()) {
            List<DataItem> stake = ((Array) distr.get(key)).getDataItems();
            List<DataItem> fraction = ((Array) stake.get(0)).getDataItems();
            BigInteger totalPoolStake = ledgerForm ? toBigInteger(stake.get(1)) : null;
            String vrfKeyHash = toHex(stake.get(ledgerForm ? 2 : 1));
            String poolId = toHex(key);
            pools.put(poolId, new IndividualPoolStake(poolId, toBigInteger(fraction.get(0)),
                    toBigInteger(fraction.get(1)), vrfKeyHash, totalPoolStake));
        }
        return new Distribution(pools, totalActiveStake);
    }
}
