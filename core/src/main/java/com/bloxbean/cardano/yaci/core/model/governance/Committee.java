package com.bloxbean.cardano.yaci.core.model.governance;

import com.bloxbean.cardano.yaci.core.model.Credential;
import lombok.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * committee = [{ committee_cold_credential => epoch }, unit_interval]
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
    private BigDecimal threshold; //TODO?? Check the correct name.
}
