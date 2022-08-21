package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.model.Era;

public class EraUtil {

    public static Era getEra(int value) {
        switch (value) {
            case 0:
            case 1:
                return Era.Byron;
            case 2:
                return Era.Shelley;
            case 3:
                return Era.Allegra;
            case 4:
                return Era.Mary;
            case 5:
                return Era.Alonzo;
            case 6:
                return Era.Babbage;
            default:
                return null;
        }
    }
}
