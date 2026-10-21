package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.Block;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.ExUnits;
import com.bloxbean.cardano.yaci.core.model.Redeemer;
import com.bloxbean.cardano.yaci.core.model.RedeemerTag;
import com.bloxbean.cardano.yaci.core.model.Witnesses;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the ADR 0011 implementation review fixes:
 * indefinite-length dispatch, era gating (a Conway block never gets Leios certificate fields),
 * redeemer fix-up independence from the datum raw field, and fail-loud required_signers parsing.
 */
class BlockSerializerDijkstraFixesTest {

    @Test
    void indefiniteLengthInnerBlockArrayRoutesToDijkstraParser() {
        Array transactions = new Array();
        Array body = blockBody(transactions, SimpleValue.NULL, SimpleValue.NULL);

        Array innerBlock = new Array();
        innerBlock.setChunked(true); //indefinite-length [header, block_body]
        innerBlock.add(headerArray());
        innerBlock.add(body);
        innerBlock.add(SimpleValue.BREAK);

        Array block = new Array();
        block.add(new UnsignedInteger(8));
        block.add(innerBlock);

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertEquals(Era.Dijkstra, parsed.getEra());
        assertTrue(parsed.getTransactionBodies().isEmpty());
        assertTrue(parsed.getInvalidTransactions().isEmpty());
    }

    @Test
    void conwayBlockNeverGetsLeiosCertificateFields() {
        //A hypothetical Conway block with >5 items must NOT grow certificate fields
        Array innerBlock = new Array();
        innerBlock.add(headerArray());
        innerBlock.add(new Array()); //transaction_bodies
        innerBlock.add(new Array()); //witness_sets
        innerBlock.add(new co.nstant.in.cbor.model.Map()); //aux data
        innerBlock.add(new Array()); //invalid txs
        innerBlock.add(certificate()); //extra trailing item
        innerBlock.add(new ByteString(new byte[]{1, 2})); //extra trailing item

        Array block = new Array();
        block.add(new UnsignedInteger(7)); //Conway
        block.add(innerBlock);

        Block parsed = BlockSerializer.INSTANCE.deserialize(CborSerializationUtil.serialize(block, false));

        assertEquals(Era.Conway, parsed.getEra());
        assertNull(parsed.getLeiosCertificate());
        assertNull(parsed.getPerasCertCbor());
    }

    @Test
    void conwayBlockJsonHasNoLeiosOrPerasKeys() throws Exception {
        //Leios fields are null for pre-Leios blocks and must not appear in their JSON (same JSON as on next)
        byte[] conway = com.bloxbean.cardano.yaci.core.util.CborLoader.getHexBytes("block/preprod292683.txt");
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(BlockSerializer.INSTANCE.deserialize(conway));

        assertTrue(json.contains("\"blockNumber\""));
        assertTrue(!json.contains("leios") && !json.contains("peras"), json.substring(0, 300));
    }

    @Test
    void redeemerFixupRunsWhenDatumRawFieldIsMissing() throws Exception {
        //Raw witness map with ONLY key 5 (redeemers) — no key 4 (datums)
        byte[] plutusDataBytes = CborSerializationUtil.serialize(new UnsignedInteger(42), false);

        Array redeemerItem = new Array();
        redeemerItem.add(new UnsignedInteger(0)); //tag
        redeemerItem.add(new UnsignedInteger(0)); //index
        redeemerItem.add(new UnsignedInteger(42)); //data
        Array exUnitsArr = new Array();
        exUnitsArr.add(new UnsignedInteger(0));
        exUnitsArr.add(new UnsignedInteger(0));
        redeemerItem.add(exUnitsArr);

        Array redeemerList = new Array();
        redeemerList.add(redeemerItem);

        co.nstant.in.cbor.model.Map witnessMap = new co.nstant.in.cbor.model.Map();
        witnessMap.put(new UnsignedInteger(5), redeemerList);
        byte[] rawWitnessBytes = CborSerializationUtil.serialize(witnessMap, false);

        //Parsed witness claims BOTH datums and redeemers, with stale re-encoded values
        Datum staleDatum = new Datum("00", "00", null);
        Redeemer staleRedeemer = Redeemer.builder()
                .tag(RedeemerTag.Spend)
                .index(0)
                .data(new Datum("stale", "stale", null))
                .exUnits(ExUnits.builder().mem(BigInteger.ZERO).steps(BigInteger.ZERO).build())
                .cbor("stale")
                .build();
        //Mutable lists: the fix-up updates datum/redeemer entries in place
        Witnesses witnesses = Witnesses.builder()
                .datums(new java.util.ArrayList<>(List.of(staleDatum)))
                .redeemers(new java.util.ArrayList<>(List.of(staleRedeemer)))
                .build();

        BlockSerializer.INSTANCE.handleWitnessDatumRedeemer(1L, List.of(witnesses), List.of(rawWitnessBytes));

        //The datum raw field was missing (logged), but the redeemer fix-up must still have run
        Redeemer fixed = witnesses.getRedeemers().get(0);
        assertEquals(HexUtil.encodeHexString(plutusDataBytes), fixed.getData().getCbor());
        assertEquals(Datum.cborToHash(plutusDataBytes), fixed.getData().getHash());
    }

    @Test
    void dijkstraCredentialGuardsKeepKeyHashSignersAndSkipScriptGuards() {
        byte[] keyHash = new byte[28];
        byte[] scriptHash = new byte[28];
        java.util.Arrays.fill(keyHash, (byte) 0x11);
        java.util.Arrays.fill(scriptHash, (byte) 0x22);
        co.nstant.in.cbor.model.Map bodyMap = new co.nstant.in.cbor.model.Map();
        bodyMap.put(new UnsignedInteger(0), new Array());
        bodyMap.put(new UnsignedInteger(1), new Array());
        bodyMap.put(new UnsignedInteger(2), new UnsignedInteger(5));
        Array guards = new Array();
        guards.add(new Array().add(new UnsignedInteger(0)).add(new ByteString(keyHash)));    //key-hash credential
        guards.add(new Array().add(new UnsignedInteger(1)).add(new ByteString(scriptHash))); //script guard
        bodyMap.put(new UnsignedInteger(14), guards);

        var body = TransactionBodySerializer.INSTANCE.deserializeDI(bodyMap,
                CborSerializationUtil.serialize(bodyMap, false));

        assertEquals(java.util.Set.of(HexUtil.encodeHexString(keyHash)), body.getRequiredSigners());
    }

    @Test
    void nonByteStringRequiredSignerFailsLoud() {
        co.nstant.in.cbor.model.Map bodyMap = new co.nstant.in.cbor.model.Map();
        bodyMap.put(new UnsignedInteger(0), new Array());
        bodyMap.put(new UnsignedInteger(1), new Array());
        bodyMap.put(new UnsignedInteger(2), new UnsignedInteger(5));
        Array requiredSigners = new Array();
        requiredSigners.add(new UnsignedInteger(7)); //not a byte string
        bodyMap.put(new UnsignedInteger(14), requiredSigners);

        byte[] bodyBytes = CborSerializationUtil.serialize(bodyMap, false);

        assertThrows(IllegalStateException.class,
                () -> TransactionBodySerializer.INSTANCE.deserializeDI(bodyMap, bodyBytes));
    }

    //w40a block_body = [transactions, leios_certificate / nil, peras_certificate / nil]
    private Array blockBody(Array transactions, DataItem certificate, DataItem peras) {
        Array blockBody = new Array();
        blockBody.add(transactions);
        blockBody.add(certificate);
        blockBody.add(peras);
        return blockBody;
    }

    private Array certificate() {
        Array certificate = new Array();
        certificate.add(new ByteString(bytes(8, 71)));
        certificate.add(new ByteString(bytes(48, 72)));
        return certificate;
    }

    private Array headerArray() {
        Array headerBody = new Array();
        headerBody.add(new UnsignedInteger(100));
        headerBody.add(new UnsignedInteger(200));
        headerBody.add(SimpleValue.NULL);
        headerBody.add(new ByteString(bytes(32, 1)));
        headerBody.add(new ByteString(bytes(32, 2)));
        headerBody.add(vrfCert(3));
        headerBody.add(new UnsignedInteger(0));
        headerBody.add(new ByteString(bytes(32, 4)));
        headerBody.add(operationalCert());
        headerBody.add(protocolVersion());

        Array headerArray = new Array();
        headerArray.add(headerBody);
        headerArray.add(new ByteString(bytes(448, 8)));
        return headerArray;
    }

    private Array vrfCert(int seed) {
        Array vrfCert = new Array();
        vrfCert.add(new ByteString(bytes(32, seed)));
        vrfCert.add(new ByteString(bytes(80, seed + 1)));
        return vrfCert;
    }

    private Array operationalCert() {
        Array operationalCert = new Array();
        operationalCert.add(new ByteString(bytes(32, 5)));
        operationalCert.add(new UnsignedInteger(1));
        operationalCert.add(new UnsignedInteger(2));
        operationalCert.add(new ByteString(bytes(64, 6)));
        return operationalCert;
    }

    private Array protocolVersion() {
        Array protocolVersion = new Array();
        protocolVersion.add(new UnsignedInteger(10));
        protocolVersion.add(new UnsignedInteger(0));
        return protocolVersion;
    }

    private byte[] bytes(int length, int seed) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }
}
