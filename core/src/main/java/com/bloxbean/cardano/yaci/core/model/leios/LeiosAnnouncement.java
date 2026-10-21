package com.bloxbean.cardano.yaci.core.model.leios;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Musashi ranking-block header extension announcing an Endorser Block.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosAnnouncement {
    private String ebHash;
    private long ebSize;
}
