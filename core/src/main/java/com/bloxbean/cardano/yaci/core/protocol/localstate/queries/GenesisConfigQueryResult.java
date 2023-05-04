package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@AllArgsConstructor
@Builder
@ToString
public class GenesisConfigQueryResult implements QueryResult {
    private int systemStartYear;
    private int systemStartDayOfYear;
    private long systemStartPicoSecondsOfDay;

    private long networkMagic;
    private long networkId;
    private double activeSlotsCoeff;
    private int securityParam;
    private int epochLength;
    private int slotsPerKesPeriod;
    private int maxKESEvolutions;
    private int slotLength;
    private int updateQuorum;
    private BigInteger maxLovelaceSupply;

    public LocalDateTime getSystemStartTime() {
        LocalDate localDate = LocalDate.ofYearDay(systemStartYear, systemStartDayOfYear);
        LocalTime localTime = LocalTime.ofNanoOfDay(systemStartPicoSecondsOfDay / 1000);
        return LocalDateTime.of(localDate, localTime);
    }
}
