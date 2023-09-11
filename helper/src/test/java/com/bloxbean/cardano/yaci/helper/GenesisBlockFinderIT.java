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

        assertThat(optional.get().getGenesisHash()).isEqualTo("5f20df933584822601f9e3f8c024eb5eb252fe8cefb24d1317dc3d432e940ebb");

        assertThat(optional.get().getFirstBlock().getHash()).isEqualTo("89d9b5a5b8ddc8d7e5a6795e9774d97faf1efea59b2caf7eaf9f8c5b32059df4");
        assertThat(optional.get().getFirstBlock().getSlot()).isEqualTo(0);

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

        assertThat(optional.get().getGenesisHash()).isEqualTo("d4b8de7a11d929a323373cbab6c1a9bdc931beffff11db111cf9d57356ee1937");

        assertThat(optional.get().getFirstBlock().getHash()).isEqualTo("9ad7ff320c9cf74e0f5ee78d22a85ce42bb0a487d0506bf60cfb5a91ea4497d2");
        assertThat(optional.get().getFirstBlock().getSlot()).isEqualTo(0);

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

        assertThat(optional.get().getGenesisHash()).isNull();

        assertThat(optional.get().getFirstBlock().getHash()).isEqualTo("268ae601af8f9214804735910a3301881fbe0eec9936db7d1fb9fc39e93d1e37");
        assertThat(optional.get().getFirstBlock().getSlot()).isEqualTo(0);

        assertThat(optional.get().getFirstBlockEra()).isEqualTo(Era.Alonzo);
    }
}
