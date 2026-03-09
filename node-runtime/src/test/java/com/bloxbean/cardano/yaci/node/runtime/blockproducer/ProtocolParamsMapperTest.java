package com.bloxbean.cardano.yaci.node.runtime.blockproducer;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolParamsMapperTest {

    @Test
    void fromNodeProtocolParam_parsesAllFields() throws IOException {
        String json = Files.readString(
                Path.of("../node-app/config/network/devnet/protocol-param.json"));
        ProtocolParams pp = ProtocolParamsMapper.fromNodeProtocolParam(json);

        // Fee structure
        assertThat(pp.getMinFeeA()).isEqualTo(44);
        assertThat(pp.getMinFeeB()).isEqualTo(155381);
        assertThat(pp.getMaxBlockSize()).isEqualTo(90112);
        assertThat(pp.getMaxTxSize()).isEqualTo(16384);
        assertThat(pp.getMaxBlockHeaderSize()).isEqualTo(1100);

        // Deposits
        assertThat(pp.getKeyDeposit()).isEqualTo("2000000");
        assertThat(pp.getPoolDeposit()).isEqualTo("500000000");

        // Pool params
        assertThat(pp.getEMax()).isEqualTo(18);
        assertThat(pp.getNOpt()).isEqualTo(500);
        assertThat(pp.getA0()).isEqualByComparingTo(new BigDecimal("0.3"));
        assertThat(pp.getRho()).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(pp.getTau()).isEqualByComparingTo(new BigDecimal("0.2"));
        assertThat(pp.getMinPoolCost()).isEqualTo("170000000");

        // Protocol version
        assertThat(pp.getProtocolMajorVer()).isEqualTo(10);
        assertThat(pp.getProtocolMinorVer()).isEqualTo(0);

        // Execution unit prices
        assertThat(pp.getPriceMem()).isNotNull();
        assertThat(pp.getPriceStep()).isNotNull();

        // Max execution units
        assertThat(pp.getMaxTxExMem()).isEqualTo("16500000");
        assertThat(pp.getMaxTxExSteps()).isEqualTo("10000000000");
        assertThat(pp.getMaxBlockExMem()).isEqualTo("72000000");
        assertThat(pp.getMaxBlockExSteps()).isEqualTo("20000000000");

        // UTXO cost
        assertThat(pp.getCoinsPerUtxoSize()).isEqualTo("4310");

        // Max value size and collateral
        assertThat(pp.getMaxValSize()).isEqualTo("5000");
        assertThat(pp.getCollateralPercent()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(pp.getMaxCollateralInputs()).isEqualTo(3);

        // Ref script cost
        assertThat(pp.getMinFeeRefScriptCostPerByte()).isEqualByComparingTo(new BigDecimal("15"));

        // Cost models
        assertThat(pp.getCostModels()).isNotNull();
        assertThat(pp.getCostModels()).containsKeys("PlutusV1", "PlutusV2", "PlutusV3");
        assertThat(pp.getCostModels().get("PlutusV1")).isNotEmpty();
        assertThat(pp.getCostModels().get("PlutusV1").get("0")).isEqualTo(100788L);
        assertThat(pp.getCostModels().get("PlutusV2")).isNotEmpty();
        assertThat(pp.getCostModels().get("PlutusV3")).isNotEmpty();

        // Conway governance
        assertThat(pp.getCommitteeMinSize()).isEqualTo(3);
        assertThat(pp.getCommitteeMaxTermLength()).isEqualTo(146);
        assertThat(pp.getGovActionLifetime()).isEqualTo(6);
        assertThat(pp.getGovActionDeposit()).isEqualTo(BigInteger.valueOf(100000000000L));
        assertThat(pp.getDrepDeposit()).isEqualTo(BigInteger.valueOf(500000000L));
        assertThat(pp.getDrepActivity()).isEqualTo(20);

        // DRep voting thresholds
        assertThat(pp.getDvtMotionNoConfidence()).isEqualByComparingTo(new BigDecimal("0.67"));
        assertThat(pp.getDvtCommitteeNormal()).isEqualByComparingTo(new BigDecimal("0.67"));
        assertThat(pp.getDvtUpdateToConstitution()).isEqualByComparingTo(new BigDecimal("0.75"));

        // Pool voting thresholds
        assertThat(pp.getPvtMotionNoConfidence()).isEqualByComparingTo(new BigDecimal("0.51"));
        assertThat(pp.getPvtPPSecurityGroup()).isEqualByComparingTo(new BigDecimal("0.51"));
    }
}
