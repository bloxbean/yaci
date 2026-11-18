package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.governance.ProposalProcedure;
import com.bloxbean.cardano.yaci.core.model.governance.actions.GovAction;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.GovActionSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.ProposalProcedureSerializer;
import com.bloxbean.cardano.yaci.core.model.serializers.governance.VotingProceduresSerializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the update and the governance fields of real transaction bodies are read, before ADR 0003 M5 moves the
 * reading onto CCL's views (D8, I6). {@code governance/bodies.txt} holds, per line, the era and the body of the smallest
 * mainnet transaction of each kind found in 118 blocks with every update and proposal kind and in a 22,319 block
 * sample: each era's updates by the keys they set, each governance action kind with and without its optional items,
 * and votes by voter, vote and anchor. {@code synthetic.txt} adds bodies for what that sample lacks. Each
 * {@code .expected.jsonl} is what Yaci read from them before M5.
 */
class GovernancePinTest {
    // Models are compared as JSON, as the voting thresholds have no equals.
    private static final ObjectMapper JSON = new ObjectMapper();

    private static List<String> lines(String resource) throws IOException {
        try (InputStream in = GovernancePinTest.class.getResourceAsStream(resource)) {
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                if (!line.isBlank())
                    lines.add(line);
            return lines;
        }
    }

    private static TransactionBody body(String line) {
        String[] parts = line.split(",");
        return TransactionBodySerializer.INSTANCE.read(HexUtil.decodeHexString(parts[1]), Era.valueOf(parts[0]));
    }

    /** The governance and update fields of a body as JSON, in the order the models keep them. */
    static String json(TransactionBody body) throws IOException {
        java.util.Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("update", body.getUpdate());
        fields.put("votingProcedures", body.getVotingProcedures());
        fields.put("proposalProcedures", body.getProposalProcedures());
        fields.put("currentTreasuryValue", body.getCurrentTreasuryValue());
        fields.put("donation", body.getDonation());
        return JSON.writeValueAsString(fields);
    }

    private static void readAsBefore(String name) throws IOException {
        List<String> bodies = lines("/governance/" + name + ".txt");
        List<String> expected = lines("/governance/" + name + ".expected.jsonl");
        assertThat(bodies).hasSameSizeAs(expected);
        for (int i = 0; i < bodies.size(); i++)
            assertThat(json(body(bodies.get(i)))).as(bodies.get(i)).isEqualTo(expected.get(i));
    }

    @Test
    void realBodiesReadAsBefore() throws IOException {
        readAsBefore("bodies");
    }

    /**
     * Bodies valid by the CDDL with what the real sample lacks: every parameter key, both nonces, every voter kind,
     * the optional items null, no confidence, the treasury value and the donation.
     */
    @Test
    void syntheticBodiesReadAsBefore() throws IOException {
        readAsBefore("synthetic");
    }

    @Test
    void directCallsReadWhatTheBodyReads() throws IOException {
        List<String> all = new ArrayList<>(lines("/governance/bodies.txt"));
        all.addAll(lines("/governance/synthetic.txt"));
        for (String line : all) {
            TransactionBody body = body(line);
            Map tree = (Map) CborSerializationUtil.deserializeOne(HexUtil.decodeHexString(line.split(",")[1]));
            DataItem update = tree.get(new UnsignedInteger(6));
            if (update != null)
                assertThat(JSON.writeValueAsString(UpdateSerializer.INSTANCE.deserializeDI(update)))
                        .isEqualTo(JSON.writeValueAsString(body.getUpdate()));
            DataItem votes = tree.get(new UnsignedInteger(19));
            if (votes != null)
                assertThat(JSON.writeValueAsString(VotingProceduresSerializer.INSTANCE.deserializeDI(votes)))
                        .isEqualTo(JSON.writeValueAsString(body.getVotingProcedures()));
            DataItem proposals = tree.get(new UnsignedInteger(20));
            if (proposals != null) {
                List<ProposalProcedure> read = new ArrayList<>();
                for (DataItem proposal : ((Array) proposals).getDataItems())
                    read.add(ProposalProcedureSerializer.INSTANCE.deserializeDI(proposal));
                assertThat(JSON.writeValueAsString(read))
                        .isEqualTo(JSON.writeValueAsString(body.getProposalProcedures()));
            }
        }
    }

    /**
     * Yano stores governance actions with {@link GovActionSerializer#serialize} and reads them back with it. The writer
     * orders a map's keys, so what is read back writes the same bytes, with its map entries perhaps in another order.
     */
    @Test
    void governanceActionsReadBackWhatIsWritten() throws IOException {
        int actions = 0;
        List<String> all = new ArrayList<>(lines("/governance/bodies.txt"));
        all.addAll(lines("/governance/synthetic.txt"));
        for (String line : all) {
            List<ProposalProcedure> proposals = body(line).getProposalProcedures();
            if (proposals == null)
                continue;
            for (ProposalProcedure proposal : proposals) {
                GovAction action = proposal.getGovAction();
                byte[] written = GovActionSerializer.INSTANCE.serialize(action);
                GovAction readBack = GovActionSerializer.INSTANCE.deserialize(written);
                assertThat(readBack).isInstanceOf(action.getClass());
                assertThat(GovActionSerializer.INSTANCE.serialize(readBack)).isEqualTo(written);
                actions++;
            }
        }
        assertThat(actions).isEqualTo(21);
    }
}
