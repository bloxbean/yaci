package com.bloxbean.cardano.yaci.helper;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.GovStateQuery;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.GovStateQueryResult;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.EnactState;
import com.bloxbean.cardano.yaci.core.protocol.localstate.queries.model.RatifyState;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class GovStateQueryIT extends BaseTest {

    private LocalClientProvider localQueryProvider;
    private LocalStateQueryClient localStateQueryClient;

    @BeforeEach
    public void setup() {
        this.localQueryProvider = new LocalClientProvider(previewNodeSocketFile, previewProtocolMagic);
        this.localStateQueryClient = localQueryProvider.getLocalStateQueryClient();
        localQueryProvider.start();
    }

    @AfterEach
    public void tearDown() {
        this.localQueryProvider.shutdown();
    }

    @Test
    void govStateQuery_allFields() {
        Mono<GovStateQueryResult> mono = localStateQueryClient
                .executeQuery(new GovStateQuery(Era.Conway));

        GovStateQueryResult result = mono.block(Duration.ofSeconds(15));
        log.info("GovStateQueryResult: {}", result);

        assertThat(result).isNotNull();

        assertCommittee(result);
        assertConstitution(result);
        assertCurrentPParams(result);
        assertPreviousPParams(result);
        assertProposals(result);
        assertNextRatifyState(result);
    }

    private void assertCommittee(GovStateQueryResult result) {
        assertThat(result.getCommittee())
                .as("committee")
                .isNotNull();
        assertThat(result.getCommittee().getThreshold())
                .as("committee threshold")
                .isNotNull();
        assertThat(result.getCommittee().getCommitteeColdCredentialEpoch())
                .as("committee cold credential -> epoch map")
                .isNotNull();
    }

    private void assertConstitution(GovStateQueryResult result) {
        assertThat(result.getConstitution())
                .as("constitution")
                .isNotNull();
        assertThat(result.getConstitution().getAnchor())
                .as("constitution anchor")
                .isNotNull();
        assertThat(result.getConstitution().getAnchor().getAnchor_url())
                .as("constitution anchor url")
                .isNotBlank();
        assertThat(result.getConstitution().getAnchor().getAnchor_data_hash())
                .as("constitution anchor data hash")
                .isNotBlank();
    }

    private void assertCurrentPParams(GovStateQueryResult result) {
        assertThat(result.getCurrentPParams())
                .as("currentPParams")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMinFeeA())
                .as("currentPParams.minFeeA")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMinFeeB())
                .as("currentPParams.minFeeB")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMaxBlockSize())
                .as("currentPParams.maxBlockSize")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMaxTxSize())
                .as("currentPParams.maxTxSize")
                .isNotNull();
        assertThat(result.getCurrentPParams().getKeyDeposit())
                .as("currentPParams.keyDeposit")
                .isNotNull();
        assertThat(result.getCurrentPParams().getPoolDeposit())
                .as("currentPParams.poolDeposit")
                .isNotNull();
        assertThat(result.getCurrentPParams().getProtocolMajorVer())
                .as("currentPParams.protocolMajorVer")
                .isNotNull();
        assertThat(result.getCurrentPParams().getCostModels())
                .as("currentPParams.costModels")
                .isNotNull();
        assertThat(result.getCurrentPParams().getPriceMem())
                .as("currentPParams.priceMem")
                .isNotNull();
        assertThat(result.getCurrentPParams().getPriceStep())
                .as("currentPParams.priceStep")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMaxTxExMem())
                .as("currentPParams.maxTxExMem")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMaxBlockExMem())
                .as("currentPParams.maxBlockExMem")
                .isNotNull();
        assertThat(result.getCurrentPParams().getCollateralPercent())
                .as("currentPParams.collateralPercent")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMaxCollateralInputs())
                .as("currentPParams.maxCollateralInputs")
                .isNotNull();
        assertThat(result.getCurrentPParams().getPoolVotingThresholds())
                .as("currentPParams.poolVotingThresholds")
                .isNotNull();
        assertThat(result.getCurrentPParams().getDrepVotingThresholds())
                .as("currentPParams.drepVotingThresholds")
                .isNotNull();
        assertThat(result.getCurrentPParams().getCommitteeMinSize())
                .as("currentPParams.committeeMinSize")
                .isNotNull();
        assertThat(result.getCurrentPParams().getGovActionLifetime())
                .as("currentPParams.govActionLifetime")
                .isNotNull();
        assertThat(result.getCurrentPParams().getGovActionDeposit())
                .as("currentPParams.govActionDeposit")
                .isNotNull();
        assertThat(result.getCurrentPParams().getDrepDeposit())
                .as("currentPParams.drepDeposit")
                .isNotNull();
        assertThat(result.getCurrentPParams().getDrepActivity())
                .as("currentPParams.drepActivity")
                .isNotNull();
        assertThat(result.getCurrentPParams().getMinFeeRefScriptCostPerByte())
                .as("currentPParams.minFeeRefScriptCostPerByte")
                .isNotNull();
    }

    private void assertPreviousPParams(GovStateQueryResult result) {
        assertThat(result.getPreviousPParams())
                .as("previousPParams")
                .isNotNull();
        assertThat(result.getPreviousPParams().getMinFeeA())
                .as("previousPParams.minFeeA")
                .isNotNull();
        assertThat(result.getPreviousPParams().getProtocolMajorVer())
                .as("previousPParams.protocolMajorVer")
                .isNotNull();
    }

    private void assertProposals(GovStateQueryResult result) {
        assertThat(result.getProposals())
                .as("proposals")
                .isNotNull();
    }

    private void assertNextRatifyState(GovStateQueryResult result) {
        RatifyState nextRatifyState = result.getNextRatifyState();
        assertThat(nextRatifyState)
                .as("nextRatifyState")
                .isNotNull();
        assertThat(nextRatifyState.getEnactedGovActions())
                .as("nextRatifyState.enactedGovActions")
                .isNotNull();
        assertThat(nextRatifyState.getExpiredGovActions())
                .as("nextRatifyState.expiredGovActions")
                .isNotNull();
        assertThat(nextRatifyState.getRatificationDelayed())
                .as("nextRatifyState.ratificationDelayed")
                .isNotNull();

        EnactState nextEnactState = nextRatifyState.getNextEnactState();
        assertThat(nextEnactState)
                .as("nextRatifyState.nextEnactState")
                .isNotNull();
        assertThat(nextEnactState.getCommittee())
                .as("nextEnactState.committee")
                .isNotNull();
        assertThat(nextEnactState.getConstitution())
                .as("nextEnactState.constitution")
                .isNotNull();
        assertThat(nextEnactState.getCurrentPParams())
                .as("nextEnactState.currentPParams")
                .isNotNull();
        assertThat(nextEnactState.getPrevPParams())
                .as("nextEnactState.prevPParams")
                .isNotNull();
        assertThat(nextEnactState.getPrevGovActionIds())
                .as("nextEnactState.prevGovActionIds")
                .isNotNull();
    }
}
