package com.bloxbean.cardano.yaci.core.model;

import com.bloxbean.cardano.yaci.core.model.certs.Certificate;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.*;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public class TransactionBody {
    private Set<TransactionInput> inputs;
    private List<TransactionOutput> outputs;
    private BigInteger fee;
    private long ttl;

    @Builder.Default
    @JsonIgnore
    private List<Certificate> certificates = new ArrayList<>();
    private List<Amount> mint;
//    private List<Witdrawal> witdrawals;
//    private

}
