package com.bloxbean.cardano.yaci.core.protocol.localtx.model;

import com.bloxbean.cardano.yaci.core.model.Era;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed transaction submission error from the Cardano node.
 * <p>
 * Errors form a tree structure following the ledger's predicate failure hierarchy
 * (e.g., LEDGER -&gt; UTXOW -&gt; UTXO -&gt; FeeTooSmallUTxO). Wrapper errors contain
 * children, while leaf errors contain the actual failure details.
 */
@Getter
@Builder
@ToString(exclude = "rawCborHex")
public class TxSubmissionError {
    private final Era era;
    private final String rule;
    private final String errorName;
    private final int tag;
    private final String message;
    @Builder.Default
    private final List<TxSubmissionError> children = Collections.emptyList();
    @Builder.Default
    private final Map<String, Object> detail = Collections.emptyMap();
    private final String rawCborHex;

    /**
     * Flatten the error tree to return only leaf errors (most-specific failures).
     */
    public List<TxSubmissionError> getLeafErrors() {
        List<TxSubmissionError> leaves = new ArrayList<>();
        collectLeaves(this, leaves);
        return leaves;
    }

    private void collectLeaves(TxSubmissionError error, List<TxSubmissionError> leaves) {
        if (error.children.isEmpty()) {
            leaves.add(error);
        } else {
            for (TxSubmissionError child : error.children) {
                collectLeaves(child, leaves);
            }
        }
    }

    /**
     * Build a multi-line nested error message showing the full error chain.
     */
    public String toFullMessage() {
        StringBuilder sb = new StringBuilder();
        buildFullMessage(this, sb, 0);
        return sb.toString().trim();
    }

    private void buildFullMessage(TxSubmissionError error, StringBuilder sb, int depth) {
        String indent = "  ".repeat(depth);
        if (error.rule != null) {
            sb.append(indent).append("[").append(error.rule).append("] ");
        } else {
            sb.append(indent);
        }
        sb.append(error.errorName != null ? error.errorName : "Unknown");
        if (error.message != null && !error.message.isEmpty()) {
            sb.append(": ").append(error.message);
        }
        sb.append("\n");
        for (TxSubmissionError child : error.children) {
            buildFullMessage(child, sb, depth + 1);
        }
    }
}
