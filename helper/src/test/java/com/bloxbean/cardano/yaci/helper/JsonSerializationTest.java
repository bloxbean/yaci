package com.bloxbean.cardano.yaci.helper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.bloxbean.cardano.yaci.helper.model.Transaction;

public class JsonSerializationTest {

    @Test
    void deserTransaction() throws JsonProcessingException {
        String txnJson = " {\n" +
                "    \"blockNumber\" : 283100,\n" +
                "    \"slot\" : 12912862,\n" +
                "    \"txHash\" : \"b9ebe459c3ba8e890f951dacb50cba6fa02cf099c6308c7abd26cf616bf26ca5\",\n" +
                "    \"body\" : {\n" +
                "      \"txHash\" : \"b9ebe459c3ba8e890f951dacb50cba6fa02cf099c6308c7abd26cf616bf26ca5\",\n" +
                "      \"cbor\" : null,\n" +
                "      \"inputs\" : [ {\n" +
                "        \"transactionId\" : \"6d2174d3956d8eb2b3e1e198e817ccf1332a599d5d7320400bfd820490d706be\",\n" +
                "        \"index\" : 0\n" +
                "      } ],\n" +
                "      \"outputs\" : [ {\n" +
                "        \"address\" : \"addr_test1qpe6s9amgfwtu9u6lqj998vke6uncswr4dg88qqft5d7f67kfjf77qy57hqhnefcqyy7hmhsygj9j38rj984hn9r57fswc4wg0\",\n" +
                "        \"amounts\" : [ {\n" +
                "          \"unit\" : \"lovelace\",\n" +
                "          \"policyId\" : null,\n" +
                "          \"assetName\" : \"lovelace\",\n" +
                "          \"quantity\" : 48000000\n" +
                "        } ],\n" +
                "        \"datumHash\" : null,\n" +
                "        \"inlineDatum\" : null,\n" +
                "        \"scriptRef\" : null\n" +
                "      } ],\n" +
                "      \"fee\" : 2000000,\n" +
                "      \"ttl\" : 0,\n" +
                "      \"certificates\" : [ ],\n" +
                "      \"withdrawals\" : null,\n" +
                "      \"update\" : null,\n" +
                "      \"auxiliaryDataHash\" : null,\n" +
                "      \"validityIntervalStart\" : 0,\n" +
                "      \"mint\" : [ ],\n" +
                "      \"scriptDataHash\" : \"192d0c0c2c2320e843e080b5f91a9ca35155bc50f3ef3bfdbc72c1711b86367e\",\n" +
                "      \"collateralInputs\" : [ {\n" +
                "        \"transactionId\" : \"99c2ef8e340d5991b1acfcb2a3bf06145ab139f0b10837fb8862cf0ec2324d03\",\n" +
                "        \"index\" : 0\n" +
                "      } ],\n" +
                "      \"requiredSigners\" : null,\n" +
                "      \"netowrkId\" : 0,\n" +
                "      \"collateralReturn\" : {\n" +
                "        \"address\" : \"addr_test1qpe6s9amgfwtu9u6lqj998vke6uncswr4dg88qqft5d7f67kfjf77qy57hqhnefcqyy7hmhsygj9j38rj984hn9r57fswc4wg0\",\n" +
                "        \"amounts\" : [ {\n" +
                "          \"unit\" : \"lovelace\",\n" +
                "          \"policyId\" : null,\n" +
                "          \"assetName\" : \"lovelace\",\n" +
                "          \"quantity\" : 7000000\n" +
                "        } ],\n" +
                "        \"datumHash\" : null,\n" +
                "        \"inlineDatum\" : null,\n" +
                "        \"scriptRef\" : null\n" +
                "      },\n" +
                "      \"totalCollateral\" : 3000000,\n" +
                "      \"referenceInputs\" : null,\n" +
                "      \"votingProcedures\" : null,\n" +
                "      \"proposalProcedures\" : null,\n" +
                "      \"currentTreasuryValue\" : null,\n" +
                "      \"donation\" : null\n" +
                "    },\n" +
                "    \"utxos\" : [ ],\n" +
                "    \"collateralReturnUtxo\" : {\n" +
                "      \"txHash\" : \"b9ebe459c3ba8e890f951dacb50cba6fa02cf099c6308c7abd26cf616bf26ca5\",\n" +
                "      \"index\" : 1,\n" +
                "      \"address\" : \"addr_test1qpe6s9amgfwtu9u6lqj998vke6uncswr4dg88qqft5d7f67kfjf77qy57hqhnefcqyy7hmhsygj9j38rj984hn9r57fswc4wg0\",\n" +
                "      \"amounts\" : [ {\n" +
                "        \"unit\" : \"lovelace\",\n" +
                "        \"policyId\" : null,\n" +
                "        \"assetName\" : \"lovelace\",\n" +
                "        \"quantity\" : 7000000\n" +
                "      } ],\n" +
                "      \"datumHash\" : null,\n" +
                "      \"inlineDatum\" : null,\n" +
                "      \"scriptRef\" : null\n" +
                "    },\n" +
                "    \"witnesses\" : {\n" +
                "      \"vkeyWitnesses\" : [ {\n" +
                "        \"key\" : \"53442c73a8bdbd7bdb59d033bd35bfb3968784eb85be3955dc025de491744f92\",\n" +
                "        \"signature\" : \"6f4815ac017228d22290a1276134c34e1a2f970e9816a9a0df0bcaa5fdcc0d636e4d13f7572ce8f826d5302a7a12fd5cfd3ca306970b714c632ddd338db96b08\"\n" +
                "      } ],\n" +
                "      \"nativeScripts\" : [ ],\n" +
                "      \"bootstrapWitnesses\" : [ ],\n" +
                "      \"plutusV1Scripts\" : [ ],\n" +
                "      \"datums\" : [ {\n" +
                "        \"cbor\" : \"19077a\",\n" +
                "        \"json\" : \"\"\n" +
                "      } ],\n" +
                "      \"redeemers\" : [ {\n" +
                "        \"cbor\" : \"840000187b820a0a\"\n" +
                "      } ],\n" +
                "      \"plutusV2Scripts\" : [ {\n" +
                "        \"type\" : \"2\",\n" +
                "        \"content\" : \"4746010000222601\"\n" +
                "      } ],\n" +
                "      \"plutusV3Scripts\" : [ ]\n" +
                "    },\n" +
                "    \"auxData\" : null,\n" +
                "    \"invalid\" : true\n" +
                "  }\n";

        ObjectMapper objectMapper = new ObjectMapper();
        Transaction transaction = objectMapper.readValue(txnJson, Transaction.class);
        System.out.println(transaction);

    }
}
