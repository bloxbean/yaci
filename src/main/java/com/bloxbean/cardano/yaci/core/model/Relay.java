package com.bloxbean.cardano.yaci.core.model;

import lombok.*;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Builder
public class Relay {
    private int port;
    private String ipv4;
    private String ipv6;
    private String dnsName;

    //TODO - Should we also add type single host addr, single dns, multi dns ???
}
