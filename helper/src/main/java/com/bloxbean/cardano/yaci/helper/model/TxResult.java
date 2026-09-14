package com.bloxbean.cardano.yaci.helper.model;

import com.bloxbean.cardano.yaci.core.protocol.localtx.model.TxSubmissionError;
import lombok.*;

import java.util.Collections;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
public class TxResult {
    private String txHash;
    private boolean accepted;
    private String errorCbor;
    @Builder.Default
    private List<TxSubmissionError> errors = Collections.emptyList();
    private String errorMessage;
}
