package com.bloxbean.cardano.yaci.core.model.byron.payload;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yaci.core.model.byron.signature.Delegation;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.*;

import java.math.BigInteger;
import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ByronDlgPayload extends Delegation {
   private BigInteger epoch;

  public static ByronDlgPayload deserialize(DataItem dataItem) {
    List<DataItem> dataItems = ((Array) dataItem).getDataItems();
    // extract dlg
    List<DataItem> dlg = ((Array) dataItems.get(0)).getDataItems();

    BigInteger epoch = ((UnsignedInteger) dlg.get(0)).getValue();
    String issuer = HexUtil.encodeHexString(((ByteString) dlg.get(1)).getBytes());
    String delegate = HexUtil.encodeHexString(((ByteString) dlg.get(2)).getBytes());
    String certificate = HexUtil.encodeHexString(((ByteString) dlg.get(3)).getBytes());


    ByronDlgPayload byronDlgPayload = new ByronDlgPayload();

    byronDlgPayload.setEpoch(epoch);
    byronDlgPayload.setIssuer(issuer);
    byronDlgPayload.setDelegate(delegate);
    byronDlgPayload.setCertificate(certificate);

    return byronDlgPayload;
  }
}
