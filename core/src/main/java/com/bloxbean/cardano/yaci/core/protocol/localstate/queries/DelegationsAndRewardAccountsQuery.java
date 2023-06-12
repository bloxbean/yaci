package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.yaci.core.protocol.handshake.messages.AcceptVersion;
import com.bloxbean.cardano.yaci.core.protocol.handshake.util.N2CVersionTableConstant;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.Era;
import com.bloxbean.cardano.yaci.core.protocol.localstate.api.EraQuery;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.*;

/**
 * Get delegations and reward balances for list of stake addresses
 */
@Getter
@ToString
@Slf4j
public class DelegationsAndRewardAccountsQuery implements EraQuery<DelegationsAndRewardAccountsResult> {
    private Era era;
    private Set<Address> stakeAddresses;
    private Map<Credential, Address> credentialAddressMap = new HashMap<>();

    /**
     * @param era            current era
     * @param stakeAddresses list of stake addresses
     */
    public DelegationsAndRewardAccountsQuery(Era era, Set<Address> stakeAddresses) {
        this.era = era;
        this.stakeAddresses = stakeAddresses;

        if (stakeAddresses == null || stakeAddresses.size() == 0)
            throw new IllegalArgumentException("Stake addresses cannot be null or empty");

        stakeAddresses.stream()
                .forEach(stakeAddress -> {
                    Credential credential = stakeAddress.getDelegationCredential()
                            .map(credBytes -> {
                                Credential cred;
                                if (stakeAddress.isStakeKeyHashInDelegationPart())
                                    cred = Credential.fromKey(credBytes);
                                else
                                    cred = Credential.fromScript(credBytes);
                                return cred;
                            }).orElseThrow(() -> new IllegalArgumentException("Delegation credential not found for address: " + stakeAddress));
                    credentialAddressMap.put(credential, stakeAddress);
                });
    }

    /**
     * List of stake addresses
     *
     * @param stakeAddresses
     */
    public DelegationsAndRewardAccountsQuery(Set<Address> stakeAddresses) {
        this(Era.Babbage, stakeAddresses);
    }

    /**
     * Format:
     * query 	[10 #6.258([ *rwdr ])]
     * rwdr 	[flag bytestring] 	bytestring is the keyhash of the staking vkey
     * flag 	0/1 	0=keyhash 1=scripthash
     * result 	[[ delegation rewards] ]
     * delegation 	{ * rwdr - poolid } 	poolid is a bytestring
     * Reference: https://arsmagna.xyz/docs/network-lsq/
     **/
    @Override
    public DataItem serialize(AcceptVersion protocolVersion) {
        Array array = new Array();
        array.add(new UnsignedInteger(10)); //tag

        //Sort credentials by Script first, then bytes based on network order integers
        List<Credential> inputCredentials = new ArrayList(credentialAddressMap.keySet());
        inputCredentials.sort(Comparator.comparing(Credential::getType, Comparator.reverseOrder())
                .thenComparing(c -> HexUtil.encodeHexString(c.getBytes())));

        //Build input cbor array
        Array credentials = new Array();
        inputCredentials.stream()
                .forEach(cred -> {
                    int flag = cred.getType() == CredentialType.Key ? 0 : 1;
                    var credentialArr = new Array();
                    credentialArr.add(new UnsignedInteger(flag));
                    credentialArr.add(new ByteString(cred.getBytes()));
                    credentials.add(credentialArr);
                });

        if (protocolVersion.getVersionNumber() <= N2CVersionTableConstant.PROTOCOL_V13) {
            credentials.setTag(258);
        }

        array.add(credentials);
        return wrapWithOuterArray(array);
    }

    @Override
    public DelegationsAndRewardAccountsResult deserializeResult(AcceptVersion protocolVersion, DataItem[] di) {
        List<DataItem> delegationRewardsDIList = extractResultArray(di[0]);
        Array delegationRewardsArray = (Array) delegationRewardsDIList.get(0);

        Map<Address, String> delegations = new LinkedHashMap<>();
        Map<Address, BigInteger> rewards = new LinkedHashMap<>();

        var delegationMap = (co.nstant.in.cbor.model.Map) delegationRewardsArray.getDataItems().get(0);
        var rewardsMap = (co.nstant.in.cbor.model.Map) delegationRewardsArray.getDataItems().get(1);

        delegationMap.getKeys().stream()
                .forEach(key -> {
                    var rwdrFlag = ((UnsignedInteger) ((Array) key).getDataItems().get(0)).getValue().intValue(); //[flag bytestring]
                    var rwdrStakingKeyHash = ((ByteString) ((Array) key).getDataItems().get(1)).getBytes();

                    Credential credential = rwdrFlag == 0 ? Credential.fromKey(rwdrStakingKeyHash) : Credential.fromScript(rwdrStakingKeyHash);
                    Address stakeAddress = credentialAddressMap.get(credential);
                    if (stakeAddress == null)
                        throw new IllegalStateException("Stake address not found for credential: " + credential);

                    var poolBytes = ((ByteString) delegationMap.get(key)).getBytes();
                    delegations.put(stakeAddress, HexUtil.encodeHexString(poolBytes));
                });

        rewardsMap.getKeys().stream()
                .forEach(key -> {
                    var rwdrFlag = ((UnsignedInteger) ((Array) key).getDataItems().get(0)).getValue().intValue(); //[flag bytestring]
                    var rwdrStakingKeyHash = ((ByteString) ((Array) key).getDataItems().get(1)).getBytes();

                    Credential credential = rwdrFlag == 0 ? Credential.fromKey(rwdrStakingKeyHash) : Credential.fromScript(rwdrStakingKeyHash);
                    Address stakeAddress = credentialAddressMap.get(credential);
                    if (stakeAddress == null)
                        throw new IllegalStateException("Stake address not found for credential: " + credential);

                    BigInteger amount = ((UnsignedInteger) rewardsMap.get(key)).getValue();
                    rewards.put(stakeAddress, amount);
                });

        return new DelegationsAndRewardAccountsResult(delegations, rewards);
    }
}
