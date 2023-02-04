package com.bloxbean.cardano.yaci.core.model.byron.signature;

import lombok.Builder;

import java.math.BigInteger;

@Builder
public class EpochRange {
  private BigInteger start;
  private BigInteger end;
}
