package com.bloxbean.cardano.yaci.core.model.certs;

import com.bloxbean.cardano.client.crypto.Bech32;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class StakePoolId {
    private String poolKeyHash;

    public StakePoolId(byte[] poolKeyHashBytes) {
        if (poolKeyHashBytes != null)
            this.poolKeyHash = HexUtil.encodeHexString(poolKeyHashBytes);
        else
            this.poolKeyHash = null;
    }

    public static StakePoolId fromHexPoolId(String poolId) {
        byte[] poolIdBytes = HexUtil.decodeHexString(poolId);
        return new StakePoolId(poolIdBytes);
    }

    public static StakePoolId fromBech32PoolId(String poolId) {
        byte[] poolIdBytes = Bech32.decode(poolId).data;
        return new StakePoolId(poolIdBytes);
    }
}
