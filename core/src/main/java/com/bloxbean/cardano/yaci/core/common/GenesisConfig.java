package com.bloxbean.cardano.yaci.core.common;

import com.bloxbean.cardano.yaci.core.model.Era;

public class GenesisConfig {
    private static GenesisConfig instance;

    private GenesisConfig() {

    }

    public static GenesisConfig getInstance() {
        if (instance == null)
            instance = new GenesisConfig();

        return instance;
    }

    public long slotDuration(Era era) {
        if (era == Era.Byron)
            return 20; //20 sec
        else
            return 1; //1 sec
    }

    public long slotsPerEpoch(Era era) {
        long totalSecsIn5DaysEpoch = 432000;
        return totalSecsIn5DaysEpoch / slotDuration(era);
    }

    public long absoluteSlot(Era era, long epoch, long slotInEpoch) {
        return (slotsPerEpoch(era) * epoch) + slotInEpoch;
    }
}
