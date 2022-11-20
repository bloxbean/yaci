package com.bloxbean.cardano.yaci.core.model.certs;

public interface Certificate {

    //TODO -- Following certificates are not yet implemented
    // pool_registration
    // pool_retirement
    // genesis_key_delegation
    // move_instantaneous_rewards_cert

//    static Certificate deserialize(Array certArray) throws CborDeserializationException {
//        Objects.requireNonNull(certArray);
//
//        List<DataItem> dataItemList = certArray.getDataItems();
//        if (dataItemList == null || dataItemList.size() < 2) {
//            throw new CborDeserializationException("Certificate deserialization failed. Invalid number of DataItem(s) : "
//                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
//        }
//
//        UnsignedInteger typeUI = (UnsignedInteger) dataItemList.get(0);
//        int type = typeUI.getValue().intValue();
//
//        Certificate certificate;
//        switch (type) {
//            case 0:
//                certificate = StakeRegistration.deserialize(certArray);
//                break;
//            case 1:
//                certificate = StakeDeregistration.deserialize(certArray);
//                break;
//            case 2:
//                certificate = StakeDelegation.deserialize(certArray);
//                break;
//            default:
//                throw new CborDeserializationException("Certificate deserialization failed. Unknown type : " + type);
//        }
//
//        return certificate;
//    }
//
//    Array serialize() throws CborSerializationException;
//
//    default String getCborHex() {
//        try {
//            return HexUtil.encodeHexString(CborSerializationUtil.serialize(serialize()));
//        } catch (CborException e) {
//            throw new CborRuntimeException("Cbor serialization error", e);
//        }
//    }
}
