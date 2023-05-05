package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StartPoint {
    private Point genesisBlock;
    private Point firstBlock;

    private Era genesisBlockEra;
    private Era firstBlockEra;
}
