package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.client.transaction.raw.CertKind;
import com.bloxbean.cardano.client.transaction.raw.RawCertificate;
import com.bloxbean.cardano.client.transaction.raw.RawPoolMetadata;
import com.bloxbean.cardano.client.transaction.raw.RawRelay;
import com.bloxbean.cardano.client.transaction.util.RawModels;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Slf4j
public enum PoolRegistrationSerializer implements Serializer<PoolRegistration> {
    INSTANCE;

    /**
     * @deprecated A decoded certificate has lost its source encoding. Use {@link CertificateSerializer#read}.
     */
    @Deprecated(since = "0.5.0", forRemoval = true)
    @Override
    public PoolRegistration deserializeDI(DataItem di) {
        return (PoolRegistration) CertificateSerializer.INSTANCE.read(CertKind.POOL_REGISTRATION, RawModels.span(di));
    }

    static PoolRegistration read(RawCertificate cert) {
        Set<String> poolOwners = new HashSet<>();
        for (byte[] poolOwner : cert.poolOwners())
            poolOwners.add(HexUtil.encodeHexString(poolOwner));

        List<Relay> relays = new ArrayList<>();
        for (RawRelay relay : cert.relays())
            relays.add(relay(relay));

        RawPoolMetadata metadata = cert.poolMetadata().orElse(null);
        var margin = cert.margin();
        return new PoolRegistration(PoolParams.builder()
                .operator(HexUtil.encodeHexString(cert.poolKeyHash()))
                .vrfKeyHash(HexUtil.encodeHexString(cert.vrfKeyHash()))
                .pledge(cert.pledge())
                .cost(cert.cost())
                .margin(new UnitInterval(margin.getNumerator(), margin.getDenominator()))
                .rewardAccount(HexUtil.encodeHexString(cert.rewardAccount()))
                .poolOwners(poolOwners)
                .relays(relays)
                .poolMetadataUrl(metadata != null ? metadata.url() : null)
                .poolMetadataHash(metadata != null ? HexUtil.encodeHexString(metadata.hash()) : null)
                .build());
    }

    private static Relay relay(RawRelay relay) {
        return switch (relay.kind()) {
            case SINGLE_HOST_ADDR ->
                    new Relay(relay.port().orElse(null), hostAddress(relay.ipv4().orElse(null)),
                            hostAddress(relay.ipv6().orElse(null)), null);
            case SINGLE_HOST_NAME -> new Relay(relay.port().orElse(null), null, null, relay.dnsName().orElse(null));
            case MULTI_HOST_NAME -> new Relay(0, null, null, relay.dnsName().orElse(null));
        };
    }

    /** Reads a relay of a node-to-client query result: Yaci is the only reader of those (ADR 0003 section 2). */
    public static Relay deserializeRelay(DataItem relayDI) {
        List<DataItem> relayItems = ((Array) relayDI).getDataItems();
        int type = toInt(relayItems.get(0));

        Integer port = 0;
        String ipv4 = null;
        String ipv6 = null;
        String dns = null;

        if (type == 0) { //Single host addr
            DataItem itemDI = relayItems.get(1);
            port = itemDI != SimpleValue.NULL ? toInt(itemDI) : null;

            itemDI = relayItems.get(2);
            if (itemDI != SimpleValue.NULL)
                ipv4 = hostAddress(toBytes(itemDI));

            //ipv6
            itemDI = relayItems.get(3);
            if (itemDI != SimpleValue.NULL)
                ipv6 = hostAddress(toBytes(itemDI));
        } else if (type == 1) {
            DataItem itemDI = relayItems.get(1);
            port = itemDI != SimpleValue.NULL? toInt(itemDI) : null;

            itemDI = relayItems.get(2);
            dns = itemDI != SimpleValue.NULL ? toUnicodeString(itemDI): null;
        } else if (type == 2) {
            DataItem itemDI = relayItems.get(1);
            dns = itemDI != SimpleValue.NULL ? toUnicodeString(itemDI): null;
        }

        return new Relay(port, ipv4, ipv6, dns);
    }

    private static String hostAddress(byte[] address) {
        if (address == null)
            return null;
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (Exception ex) {
            log.error("Unable to convert byte[] to an IP address, {}", HexUtil.encodeHexString(address), ex);
            return null;
        }
    }
}
