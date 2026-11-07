package com.bloxbean.cardano.yaci.core.model.serializers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0003 I16: a block of an era before Dijkstra maps to byte-identical JSON before and after M7c. {@code
 * compat/m7c-json-pin.txt} holds, per {@link ModelJsonPin} entry, the SHA-256 of the JSON Yaci wrote at the M7a commit
 * (51e7870) and of that JSON re-read and written again, both taken from a build of that commit. Every entry before
 * Dijkstra must match byte for byte. A Dijkstra block gains D4b's fields, so its JSON must match once they are taken
 * out.
 */
class ModelJsonPinTest {
    /** The JSON names of D4b's fields, which no model had before M7c. */
    private static final Set<String> D4B_FIELDS = Set.of("leiosCertificate", "perasCertificate", "leiosCertInBody",
            "ebAnnouncement", "guards", "subTransactions", "requiredTopLevelGuards", "directDeposits",
            "accountBalanceIntervals", "startingAccountBalanceIntervals", "plutusV4Scripts", "blsKey",
            "maxRefScriptSizePerBlock", "maxRefScriptSizePerTx", "refScriptCostStride", "refScriptCostMultiplier",
            "maxPledgeLeverage", "minPoolMargin", "leiosAnnouncementPeriodLength", "leiosVotePeriodLength",
            "leiosDiffusionPeriodLength", "leiosCommitteeSize", "leiosQuorumStakeThreshold",
            "maxEndorserBlockReferencesSize", "maxEndorserBlockTxsSize", "maxEndorserBlockExMem",
            "maxEndorserBlockExSteps", "maxRefScriptSizePerEndorserBlock", "perasMinCandidateBlockAge",
            "perasHealingFactor", "perasCertBoost", "perasTargetCommitteeSize", "perasBootstrapRound",
            "perasQuorumThresholdSafetyMargin", "refInputsCostPerMultiAssetPolicy", "refInputsCostPerDatumByte");

    @Test
    void everyFixtureMapsToTheJsonOfM7a() throws Exception {
        List<String> pins = ModelJsonPin.lines("/compat/m7c-json-pin.txt");
        List<String> changed = new ArrayList<>();
        int dijkstra = 0;
        int dijkstraFields = 0;
        for (String pin : pins) {
            String[] parts = pin.split(" ");
            String json = ModelJsonPin.json(parts[0]);
            if (!parts[0].contains("/dijkstra/")) {
                if (!sha256(json).equals(parts[1]))
                    changed.add(parts[0]);
                continue;
            }
            dijkstra++;
            JsonNode tree = ModelJsonPin.JSON.readTree(json);
            dijkstraFields += strip(tree);
            if (!sha256(ModelJsonPin.JSON.writeValueAsString(tree)).equals(parts[2]))
                changed.add(parts[0]);
        }
        assertThat(pins).hasSize(148);
        assertThat(dijkstra).isEqualTo(10);
        // The Leios fields of the four Musashi blocks' 12-item headers, at least
        assertThat(dijkstraFields).isGreaterThanOrEqualTo(8);
        assertThat(changed).as("entries whose JSON changed").isEmpty();
    }

    /** Takes D4b's fields out of a tree, and counts them. */
    private static int strip(JsonNode node) {
        int removed = 0;
        if (node instanceof ObjectNode object) {
            for (String field : D4B_FIELDS)
                if (object.remove(field) != null)
                    removed++;
        }
        for (JsonNode child : node)
            removed += strip(child);
        return removed;
    }

    private static String sha256(String json) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
    }
}
