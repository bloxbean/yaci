package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.math.BigInteger;
import java.util.Map;

/**
 * Result of DelegationsAndRewardAccountsQuery. Contains delegations and rewards for stake addresses
 * Delegations are represented as Map of Stake address -> Delegated pool id in hex
 * Rewards are represented as Map of Stake address -> Rewards
 */
@Getter
@AllArgsConstructor
@ToString
public class DelegationsAndRewardAccountsResult implements QueryResult {
    //Stake address -> Delegated pool id
    private Map<Address, String> delegations;
    //Stake address -> Rewards
    private Map<Address, BigInteger> rewards;

}
