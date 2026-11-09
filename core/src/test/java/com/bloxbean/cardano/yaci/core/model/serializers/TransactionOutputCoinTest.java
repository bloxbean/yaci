package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** An output's coin read through the one scalar read; a bignum content is an unsigned magnitude. */
class TransactionOutputCoinTest {
    private static final String ADDRESS = "581d60" + "11".repeat(28);

    @Test
    void bignumCoin() {
        assertThat(coin("c249" + "ff".repeat(9))).isEqualTo(BigInteger.ONE.shiftLeft(72).subtract(BigInteger.ONE));
        assertThat(coin("c34100")).isEqualTo(BigInteger.ONE.negate());
    }

    @Test
    void integerCoin() {
        assertThat(coin("1a000f4240")).isEqualTo(BigInteger.valueOf(1000000));
    }

    @Test
    void bignumWithAnotherTagGivesNoAmount() {
        TransactionOutput output = output("c44101");
        assertThat(output.getAmounts()).isEmpty();
    }

    private static BigInteger coin(String coin) {
        return output(coin).getAmounts().get(0).getQuantity();
    }

    private static TransactionOutput output(String coin) {
        return TransactionOutputSerializer.INSTANCE.deserializeDI(
                CborSerializationUtil.deserializeOne(HexUtil.decodeHexString("82" + ADDRESS + coin)));
    }
}
