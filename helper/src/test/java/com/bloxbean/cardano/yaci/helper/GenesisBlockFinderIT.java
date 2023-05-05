package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.common.Constants;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.helper.model.StartPoint;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GenesisBlockFinderIT {

    @Test
    void getGenesisAndFirstBlock_mainnet() {
        GenesisBlockFinder genesisBlockFinder = new GenesisBlockFinder(Constants.MAINNET_IOHK_RELAY_ADDR,
                Constants.MAINNET_IOHK_RELAY_PORT, Constants.MAINNET_PROTOCOL_MAGIC);
        Optional<StartPoint> optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional.isPresent()).isTrue();
        System.out.println(optional.get());

        optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional).isNotEmpty();
        assertThat(optional.get().getFirstBlock()).isNotNull();
        assertThat(optional.get().getGenesisBlockEra()).isEqualTo(Era.Byron);
        assertThat(optional.get().getFirstBlockEra()).isEqualTo(Era.Byron);
    }

    @Test
    void getGenesisAndFirstBlock_preprod() {
        GenesisBlockFinder genesisBlockFinder = new GenesisBlockFinder(Constants.PREPROD_IOHK_RELAY_ADDR,
                Constants.PREPROD_IOHK_RELAY_PORT, Constants.PREPROD_PROTOCOL_MAGIC);
        Optional<StartPoint> optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional.isPresent()).isTrue();
        System.out.println(optional.get());

        optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional).isNotEmpty();
        assertThat(optional.get().getFirstBlock()).isNotNull();
        assertThat(optional.get().getGenesisBlockEra()).isEqualTo(Era.Byron);
        assertThat(optional.get().getFirstBlockEra()).isEqualTo(Era.Byron);
    }

    @Test
    void getGenesisAndFirstBlock_preview() {
        GenesisBlockFinder genesisBlockFinder = new GenesisBlockFinder(Constants.PREVIEW_IOHK_RELAY_ADDR,
                Constants.PREVIEW_IOHK_RELAY_PORT, Constants.PREVIEW_PROTOCOL_MAGIC);
        Optional<StartPoint> optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional.isPresent()).isTrue();
        System.out.println(optional.get());

        optional = genesisBlockFinder.getGenesisAndFirstBlock();

        assertThat(optional).isNotEmpty();
        assertThat(optional.get().getFirstBlock()).isNotNull();
        assertThat(optional.get().getGenesisBlockEra()).isEqualTo(Era.Alonzo);
        assertThat(optional.get().getFirstBlockEra()).isEqualTo(Era.Alonzo);
    }
}
