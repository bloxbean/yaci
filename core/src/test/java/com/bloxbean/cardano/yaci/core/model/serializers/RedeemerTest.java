package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.CborException;
import com.bloxbean.cardano.yaci.core.model.Datum;
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

class RedeemerTest {
    private static final String RAW_CBOR_BLOCK_286677_PREPROD = "preprod286677.txt";
    private static final String RAW_CBOR_BLOCK_286853_PREPROD = "preprod286853.txt";
    private static final String RAW_CBOR_BLOCK_287339_PREPROD = "preprod287339.txt";
    private static final String RAW_CBOR_BLOCK_287361_PREPROD = "preprod287361.txt";
    private static final String RAW_CBOR_BLOCK_292507_PREPROD = "preprod292507.txt";
    private static final String RAW_CBOR_BLOCK_292683_PREPROD = "preprod292683.txt";
    private static final String RAW_CBOR_BLOCK_1300024_PREVIEW = "preview1300024.txt";
    private static final BigInteger REDEEMER_WITNESS_KEY = BigInteger.valueOf(5);
    private static final int REDEEMER_DATA_INDEX = 2;

    @Test
    void testBlock286677Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_286677_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "1357bc293b573c77ee85420b2057e0b2ac63107b4d1094bc60740ef33fab7d9e"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock286853Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_286853_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "07056b21a63664c1d022c747c0d00592319a3a164cf499540facb53dec50828d"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock287339Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_287339_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "ebee87fe3216dd9999ffb734e907089f74b8df6f7bc88a3c4d6dc4e7712c1a11"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock287361Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_287361_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "31756ccdba4e3a9b9edae0cab077691b25a2900af828d653ad951541c0601285"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock292507Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_292507_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "29e9f6a76f2d64f1893286d22ea57cc312f8e4a000a3abcc77082743474cfd08"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock292683Preprod() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_292683_PREPROD);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "247b9a0fae8d12418b3376a3d00f7ed1a94be9846c6cb1d1a01d3271e56a3571"
        );

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
            }
        }
    }

    @Test
    void testBlock1300024Preview() throws CborException {
        byte[] rawBlockBytes = getBlockFromResource(RAW_CBOR_BLOCK_1300024_PREVIEW);
        List<byte[]> witnessRawData = WitnessUtil.getWitnessRawData(rawBlockBytes);

        Set<String> expectedHashes = Set.of(
                "8392f0c940435c06888f9bdb8c74a95dc69f156367d6a089cf008ae05caae01e",
                "a3f5294827ecc8512355d6488118392d03f6408b26c8090fc71aa5a3e7bde42a",
                "4d140ac3cfed56070b39082beacdb250f256f302a3fd9c5d58e91f9316e93fef"
        );
        int[] expectedRedeemersSizes = new int[]{1, 74};
        int currentWitnessIdx = 0;

        for (byte[] witnessRawBytes : witnessRawData) {
            Map<BigInteger, byte[]> witnessFields = WitnessUtil.getWitnessFields(witnessRawBytes);
            byte[] redeemerArrayBytes = witnessFields.get(REDEEMER_WITNESS_KEY);
            if (redeemerArrayBytes == null || redeemerArrayBytes.length == 0) {
                continue;
            }

            List<byte[]> redeemerArraysBytes = WitnessUtil.getArrayBytes(redeemerArrayBytes);
            int expectedRedeemersSize = expectedRedeemersSizes[currentWitnessIdx++];

            for (byte[] redeemerBytes : redeemerArraysBytes) {
                List<byte[]> redeemerFieldsBytes = WitnessUtil.getRedeemerFields(redeemerBytes);
                byte[] redeemerDataBytes = redeemerFieldsBytes.get(REDEEMER_DATA_INDEX);
                String hash = Datum.cborToHash(redeemerDataBytes);
                Assertions.assertTrue(expectedHashes.contains(hash));
                Assertions.assertEquals(expectedRedeemersSize, redeemerArraysBytes.size());
            }
        }
    }

    private static byte[] getBlockFromResource(String path) {
        String filePath = "block/" + path;
        return CborLoader.getHexBytes(filePath);
    }
}

