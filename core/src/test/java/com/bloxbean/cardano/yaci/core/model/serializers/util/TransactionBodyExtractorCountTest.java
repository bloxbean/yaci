package com.bloxbean.cardano.yaci.core.model.serializers.util;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.bloxbean.cardano.yaci.core.util.Tuple;
import co.nstant.in.cbor.model.DataItem;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction-body counts across every array-length encoding. A definite-length array whose one-byte count is 128-255
 * ({@code 98 80}..{@code 98 ff}) was read as a negative count, so the block came back with no transaction bodies at all
 * and no error (Cardano mainnet block 14,053,493: 137 txs, {@code 98 89}).
 */
class TransactionBodyExtractorCountTest {

    @TestFactory
    Stream<DynamicTest> everyTransactionBodyIsExtractedForEveryCountAndHeaderWidth() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int count : new int[]{0, 1, 23, 24, 30, 127, 128, 137, 200, 255, 256, 300}) {
            for (int width : new int[]{0, 1, 2, 4, -1}) {
                if (width == 0 && count > 23 || width == 1 && count > 255) continue;
                tests.add(DynamicTest.dynamicTest("count=" + count + " width=" + width, () -> {
                    List<String> bodies = new ArrayList<>();
                    StringBuilder txBodies = new StringBuilder(header(count, width));
                    for (int i = 0; i < count; i++) {
                        String body = "a102" + uint(i); // {2: i}, a distinct fee so order is checked too
                        bodies.add(body);
                        txBodies.append(body);
                    }
                    if (width == -1) txBodies.append("ff");
                    // [era 7, [header, tx_bodies, witness_sets, auxiliary_data, invalid_txs]]
                    byte[] block = HexUtil.decodeHexString("8207" + "85" + "00" + txBodies + "80" + "a0" + "80");

                    List<Tuple<DataItem, byte[]>> extracted = TransactionBodyExtractor.getTxBodiesFromBlock(block);

                    assertThat(extracted).hasSize(count);
                    for (int i = 0; i < count; i++) {
                        assertThat(HexUtil.encodeHexString(extracted.get(i)._2)).isEqualTo(bodies.get(i));
                    }
                }));
            }
        }
        return tests.stream();
    }

    /** Array header for {@code count} items: width 0 = immediate, 1/2/4 = that many argument bytes, -1 = indefinite. */
    private static String header(int count, int width) {
        switch (width) {
            case 0: return String.format("%02x", 0x80 + count);
            case 1: return "98" + String.format("%02x", count);
            case 2: return "99" + String.format("%04x", count);
            case 4: return "9a" + String.format("%08x", count);
            default: return "9f";
        }
    }

    private static String uint(int value) {
        if (value < 24) return String.format("%02x", value);
        if (value < 256) return "18" + String.format("%02x", value);
        return "19" + String.format("%04x", value);
    }
}
