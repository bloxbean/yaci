package com.bloxbean.cardano.yaci.core.model.byron.signature;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;

import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class Signature implements  BlockSignature{

  private String blockSignature;

  @Override
  public String getType() {
    return ByronSigType.SIGNATURE;
  }

  public static Signature deserialize(DataItem dataItem){
    List<DataItem> dataItems = ((Array) dataItem).getDataItems();

    String signature = HexUtil.encodeHexString((
        (ByteString)dataItems.get(0)).getBytes()
    );

    return Signature.builder()
        .blockSignature(signature)
        .build();
  }
}
