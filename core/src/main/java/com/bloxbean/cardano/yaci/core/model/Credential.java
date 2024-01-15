package com.bloxbean.cardano.yaci.core.model;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.VerificationKey;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.math.BigInteger;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@EqualsAndHashCode
@ToString
public class Credential {
    private StakeCredType type;
    private String hash;

    private Credential(StakeCredType type, byte[] hashBytes) {
        this.type = type;
        if (hashBytes != null)
            this.hash = HexUtil.encodeHexString(hashBytes);
        else
            this.hash = null;
    }

    public static Credential fromKey(VerificationKey vkey) {
        Credential stakeCredential = fromKey(vkey.getBytes());
        return stakeCredential;
    }

    public static Credential fromKey(byte[] key) {
        byte[] keyHash = Blake2bUtil.blake2bHash224(key);
        Credential stakeCredential = new Credential(StakeCredType.ADDR_KEYHASH, keyHash);
        return stakeCredential;
    }

    public static Credential fromKeyHash(byte[] keyHash) {
        Credential stakeCredential = new Credential(StakeCredType.ADDR_KEYHASH, keyHash);
        return stakeCredential;
    }

    public static Credential fromScriptHash(byte[] scriptHash) {
        Credential stakeCredential = new Credential(StakeCredType.SCRIPTHASH, scriptHash);
        return stakeCredential;
    }

    public static Credential deserialize(Array stakeCredArray)  {
        List<DataItem> dataItemList = stakeCredArray.getDataItems();
        if (dataItemList == null || dataItemList.size() != 2)
            throw new CborRuntimeException("Credential deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));

        UnsignedInteger typeDI = (UnsignedInteger) dataItemList.get(0);
        ByteString hashDI = (ByteString) dataItemList.get(1);

        BigInteger typeBI = typeDI.getValue();
        if (typeBI.intValue() == 0) {
            return Credential.fromKeyHash(hashDI.getBytes());
        } else if (typeBI.intValue() == 1) {
            return Credential.fromScriptHash(hashDI.getBytes());
        } else {
            throw new CborRuntimeException("Credential deserialization failed. Invalid CredType : "
                    + typeBI.intValue());
        }
    }

    public Array serialize()  {
        Array array = new Array();
        if (type == StakeCredType.ADDR_KEYHASH) {
            array.add(new UnsignedInteger(0));
        } else if (type == StakeCredType.SCRIPTHASH) {
            array.add(new UnsignedInteger(1));
        } else {
            throw new CborRuntimeException("Invalid credential type : " + type);
        }

        array.add(new ByteString(HexUtil.decodeHexString(hash)));
        return array;
    }

    @JsonIgnore
    public String getCborHex() {
        return HexUtil.encodeHexString(CborSerializationUtil.serialize(serialize()));
    }

}
