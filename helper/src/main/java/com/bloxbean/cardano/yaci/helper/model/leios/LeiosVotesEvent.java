package com.bloxbean.cardano.yaci.helper.model.leios;

import com.bloxbean.cardano.yaci.core.model.leios.LeiosVote;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Batch of Leios votes delivered from notify messages when vote delivery is enabled.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class LeiosVotesEvent {
    @Builder.Default
    private List<LeiosVote> votes = new ArrayList<>();
}
