package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.helper.model.StartPoint;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GenesisBlockFinderTest {

    @Test
    void getGenesisAndFirstBlock() {
        GenesisBlockFinder genesisBlockFinder = new GenesisBlockFinder(Constants.PREPOD_IOHK_RELAY_ADDR,
                Constants.PREPOD_IOHK_RELAY_PORT, Constants.PREPOD_PROTOCOL_MAGIC);
        Optional<StartPoint> optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional.isPresent()).isTrue();
        System.out.println(optional.get());

        optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional).isNotEmpty();
        assertThat(optional.get().getFirstBlock()).isNotNull();
    }
}
