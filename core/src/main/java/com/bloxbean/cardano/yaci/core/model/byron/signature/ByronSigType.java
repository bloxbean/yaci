package com.bloxbean.cardano.yaci.core.model.byron.signature;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.experimental.FieldDefaults;

@Getter
@FieldDefaults(level = AccessLevel.PUBLIC, makeFinal = true)
public final class ByronSigType {
  private ByronSigType(){

  }
  public static final String SIGNATURE = "SIGNATURE";
  public static final String DLG_SIGNATURE = "DLG_SIGNATURE";
  public static final String LWDLG_SIGNATURE = "LWDLG_SIGNATURE";
}
