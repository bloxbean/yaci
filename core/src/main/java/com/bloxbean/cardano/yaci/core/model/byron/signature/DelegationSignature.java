package com.bloxbean.cardano.yaci.core.model.byron.signature;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.*;

import java.math.BigInteger;
import java.util.List;

@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DelegationSignature extends CommonSignature {

  private BigInteger epoch;

  @Override
  public String getType() {
    return ByronSigType.DLG_SIGNATURE;
  }

  public static DelegationSignature deserialize(DataItem dataItem) {
    List<DataItem> dataItems = ((Array) dataItem).getDataItems();
    // extract signature
    String signature = HexUtil.encodeHexString(((ByteString) dataItems.get(1)).getBytes());
    // extract dlg
    List<DataItem> dlg = ((Array) dataItems.get(0)).getDataItems();

    BigInteger epoch = ((UnsignedInteger) dlg.get(0)).getValue();
    String issuer = HexUtil.encodeHexString(((ByteString) dlg.get(1)).getBytes());
    String delegate = HexUtil.encodeHexString(((ByteString) dlg.get(2)).getBytes());
    String certificate = HexUtil.encodeHexString(((ByteString) dlg.get(3)).getBytes());


    DelegationSignature delegationSignature = new DelegationSignature();

    delegationSignature.setSignature(signature);
    delegationSignature.setEpoch(epoch);
    delegationSignature.setIssuer(issuer);
    delegationSignature.setDelegate(delegate);
    delegationSignature.setCertificate(certificate);

    return delegationSignature;
  }
}
