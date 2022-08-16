package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.Getter;

@Getter
public class StakeRegistration implements Certificate {
    private final CertificateType type = CertificateType.STAKE_REGISTRATION;

    private final StakeCredential stakeCredential;

    public StakeRegistration(StakeCredential stakeCredential) {
        this.stakeCredential = stakeCredential;
    }


//    @Override
//    public Array serialize() throws CborSerializationException {
//        if (stakeCredential == null)
//            throw new CborSerializationException("StakeRegistration serialization failed. StakeCredential is NULL");
//
//        Array array = new Array();
//        array.add(new UnsignedInteger(0));
//
//        array.add(stakeCredential.serialize());
//        return array;
//    }
}
