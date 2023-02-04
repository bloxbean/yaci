package com.bloxbean.cardano.yaci.core.model.byron.signature;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.NonNull;

import java.math.BigInteger;
import java.util.List;

/**
 * https://github.com/input-output-hk/cardano-ledger/blob/master/eras/byron/cddl-spec/byron.cddl
 * blocksig = [0, signature] / [1, lwdlgsig]  / [2, dlgsig]
 * <p>
 * lwdlgsig = [lwdlg, signature] lwdlg = [ epochRange : [epochid, epochid] , issuer : pubkey ,
 * delegate : pubkey , certificate : signature]
 * <p>
 * dlgsig = [dlg, signature] dlg = [ epoch : epochid, issuer : pubkey , delegate : pubkey ,
 * certificate : signature]
 */

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Signature.class, name = ByronSigType.SIGNATURE),
    @JsonSubTypes.Type(value = DelegationSignature.class, name = ByronSigType.DLG_SIGNATURE),
    @JsonSubTypes.Type(value = LightWeightSignature.class, name = ByronSigType.LWDLG_SIGNATURE),
})
public interface BlockSignature {

  @JsonIgnore
  String getType();

  static BlockSignature deserialize(@NonNull DataItem dataItem)
      throws CborDeserializationException {
    List<DataItem> blockSig = ((Array) dataItem).getDataItems();
    BigInteger type = ((UnsignedInteger) blockSig.get(BigInteger.ZERO.intValue())).getValue();

    DataItem blockSigContent = blockSig.get(BigInteger.ONE.intValue());

    if (type.equals(
        BigInteger.ZERO)) { // not yet validated, no LightWeightSignature found on mainnet and test net
      return Signature.deserialize(blockSigContent);
    } else if (type.equals(
        BigInteger.ONE)) { // not yet validated, no LightWeightSignature found on mainnet and test net
      return LightWeightSignature.deserialize(blockSigContent);
    } else if (type.equals(BigInteger.TWO)) {
      return DelegationSignature.deserialize(blockSigContent);
    } else {
      throw new CborDeserializationException(
          "Cbor deserialization failed. Invalid type. " + dataItem);
    }

  }

}
