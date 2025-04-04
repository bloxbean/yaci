package com.bloxbean.cardano.yaci.core.model.governance;

import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import lombok.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@literal
 * committee = [{ committee_cold_credential => epoch }, unit_interval]
 * }
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class Committee {
    @Builder.Default
    private Map<Credential, Long> committeeColdCredentialEpoch = new LinkedHashMap<>();
    private UnitInterval threshold; //TODO?? Check the correct name.
}
