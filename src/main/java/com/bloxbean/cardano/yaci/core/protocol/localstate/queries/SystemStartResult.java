package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@AllArgsConstructor
@ToString
public class SystemStartResult implements QueryResult {
    private int year;
    private int dayOfYear;
    private long picoSecondsOfDay;

    public LocalDateTime getLocalDateTime() {
        LocalDate localDate = LocalDate.ofYearDay(year, dayOfYear);
        LocalTime localTime = LocalTime.ofNanoOfDay(picoSecondsOfDay / 1000);
        return LocalDateTime.of(localDate, localTime);
    }
}
