package com.bloxbean.cardano.yaci.core.model.certs;

import lombok.Getter;

@Getter
public class StakeDeregistration implements Certificate {
    private final CertificateType type = CertificateType.STAKE_DEREGISTRATION;

    private final StakeCredential stakeCredential;

    public StakeDeregistration(StakeCredential stakeCredential) {
        this.stakeCredential = stakeCredential;
    }

//    public static StakeDeregistration deserialize(Array stDeregArray) throws CborDeserializationException {
//        Objects.requireNonNull(stDeregArray);
//
//        List<DataItem> dataItemList = stDeregArray.getDataItems();
//        if (dataItemList == null || dataItemList.size() != 2) {
//            throw new CborDeserializationException("StakeDeregistration deserialization failed. Invalid number of DataItem(s) : "
//                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
//        }
//
//        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
//        if (type == null || type.getValue().intValue() != 1)
//            throw new CborDeserializationException("StakeDeregistration deserialization failed. Invalid type : "
//                    + type != null ? String.valueOf(type.getValue().intValue()) : null);
//
//        Array stakeCredArray = (Array) dataItemList.get(1);
//
//        StakeCredential stakeCredential = StakeCredential.deserialize(stakeCredArray);
//
//        return new StakeDeregistration(stakeCredential);
//    }
//
//    @Override
//    public Array serialize() throws CborSerializationException {
//        if (stakeCredential == null)
//            throw new CborSerializationException("StakeDeregistration serialization failed. StakeCredential is NULL");
//
//        Array array = new Array();
//        array.add(new UnsignedInteger(1));
//
//        array.add(stakeCredential.serialize());
//        return array;
//    }
}
