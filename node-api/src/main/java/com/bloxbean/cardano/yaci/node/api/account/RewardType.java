package com.bloxbean.cardano.yaci.node.api.account;

/**
 * Types of rewards that can be earned on the Cardano network.
 */
public enum RewardType {
    /** Pool delegation reward for delegators (Shelley+) */
    MEMBER,
    /** Pool operator reward — cost + margin (Shelley+) */
    LEADER,
    /** Pool deposit return on retirement (Shelley+) */
    REFUND,
    /** MIR from treasury (Shelley-Babbage) */
    MIR_TREASURY,
    /** MIR from reserves (Shelley-Babbage) */
    MIR_RESERVES,
    /** Governance proposal deposit return (Conway+) */
    PROPOSAL_REFUND,
    /** Approved treasury withdrawal (Conway+) */
    TREASURY_WITHDRAWAL
}
