package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.Getter;

@Getter
public class StakeDelegation implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DELEGATION;

    private final StakeCredential stakeCredential;
    private final StakePoolId stakePoolId;

    public StakeDelegation(StakeCredential stakeCredential, StakePoolId stakePoolId) {
        this.stakeCredential = stakeCredential;
        this.stakePoolId = stakePoolId;
    }



//    @Override
//    public Array serialize()  {
//        if (stakeCredential == null)
//            throw new CborSerializationException("StakeDelegation serialization failed. StakeCredential is NULL");
//
//        Array array = new Array();
//        array.add(new UnsignedInteger(2));
//
//        array.add(stakeCredential.serialize());
//        array.add(new ByteString(stakePoolId.getPoolKeyHash()));
//        return array;
//    }
}
