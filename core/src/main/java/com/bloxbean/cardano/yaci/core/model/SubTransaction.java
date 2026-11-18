package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

/**
 * A sub-transaction of a Dijkstra transaction body (key 23). Its id is {@code body.txHash}, the hash of its body as
 * encoded. A sub-transaction body has no fee, collateral, collateral return, total collateral, sub-transactions or
 * starting account balance intervals, so those fields of {@code body} are null.
 * <p>
 * Experimental: Dijkstra is not final; this may change in any release.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class SubTransaction {
    private TransactionBody body;
    private Witnesses witnesses;
    /** The auxiliary data, or null if it has none. */
    private AuxData auxData;
}
