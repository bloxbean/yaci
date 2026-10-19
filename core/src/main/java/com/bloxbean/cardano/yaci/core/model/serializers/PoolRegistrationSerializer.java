package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.*;
import com.bloxbean.cardano.yaci.core.exception.CborRuntimeException;
import com.bloxbean.cardano.yaci.core.model.PoolParams;
import com.bloxbean.cardano.yaci.core.model.Relay;
import com.bloxbean.cardano.yaci.core.model.certs.PoolRegistration;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.types.UnitInterval;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.bloxbean.cardano.yaci.core.util.CborSerializationUtil.*;

@Slf4j
public enum PoolRegistrationSerializer implements Serializer<PoolRegistration> {
    INSTANCE;

    @Override
    public PoolRegistration deserializeDI(DataItem di) {
        Array poolRegistrationArr = (Array) di;

        List<DataItem> dataItemList = poolRegistrationArr.getDataItems();
        //Dijkstra adds an optional bls_key after vrf_keyhash (11 items); earlier eras always have 10
        if (dataItemList == null || (dataItemList.size() != 10 && dataItemList.size() != 11)) {
            throw new CborRuntimeException("PoolRegistration deserialization failed. Invalid number of DataItem(s) : "
                    + (dataItemList != null ? String.valueOf(dataItemList.size()) : null));
        }

        UnsignedInteger type = (UnsignedInteger) dataItemList.get(0);
        if (type == null || type.getValue().intValue() != 3)
            throw new CborRuntimeException("PoolRegistration deserialization failed. Invalid type : "
                    + type != null ? String.valueOf(type.getValue().intValue()) : null);

        //List<DataItem> paramDataItems = ((Array)dataItemList.get(1)).getDataItems();
        String operator = toHex(dataItemList.get(1));
        String vrfKeyHash = toHex(dataItemList.get(2));
        int o = dataItemList.size() - 10; //1 when a Dijkstra bls_key (or nil) sits at index 3, else 0
        String blsPublicKey = null;
        String blsPossessionProof = null;
        if (o == 1 && !SimpleValue.NULL.equals(dataItemList.get(3))) {
            List<DataItem> blsKey = ((Array) dataItemList.get(3)).getDataItems();
            blsPublicKey = toHex(blsKey.get(0));
            blsPossessionProof = toHex(blsKey.get(1));
        }
        BigInteger pledge = toBigInteger(dataItemList.get(3 + o));
        BigInteger cost = toBigInteger(dataItemList.get(4 + o));

        UnitInterval margin = toUnitInterval(dataItemList.get(5 + o));
        String rewardAccount = toHex(dataItemList.get(6 + o));

        //Pool Owners0
        Set<String> poolOwners = new HashSet<>();
        List<DataItem> poolOwnersDataItems = ((Array) dataItemList.get(7 + o)).getDataItems();
        for (DataItem poolOwnerDI : poolOwnersDataItems) {
            if (poolOwnerDI == SimpleValue.BREAK)
                continue;
            poolOwners.add(toHex(poolOwnerDI));
        }

        //Relays
        List<DataItem> relaysDataItems = ((Array) dataItemList.get(8 + o)).getDataItems();
        List<Relay> relays = new ArrayList<>();
        for (DataItem relayDI : relaysDataItems) {
            if (relayDI == SimpleValue.BREAK)
                continue;
            relays.add(deserializeRelay(relayDI));
        }

        //pool metadata
        DataItem poolMetaDataDI = dataItemList.get(9 + o);
        String metadataUrl = null;
        String metadataHash = null;
        if (poolMetaDataDI != SimpleValue.NULL) {
            List<DataItem> poolMetadataDataItems = ((Array) poolMetaDataDI).getDataItems();
            metadataUrl = toUnicodeString(poolMetadataDataItems.get(0));
            metadataHash = toHex(poolMetadataDataItems.get(1));
        }

        PoolParams poolParams = PoolParams.builder()
                .operator(operator)
                .vrfKeyHash(vrfKeyHash)
                .pledge(pledge)
                .cost(cost)
                .margin(margin)
                .rewardAccount(rewardAccount)
                .poolOwners(poolOwners)
                .relays(relays)
                .poolMetadataUrl(metadataUrl)
                .poolMetadataHash(metadataHash)
                .blsPublicKey(blsPublicKey)
                .blsPossessionProof(blsPossessionProof)
                .build();

        return new PoolRegistration(poolParams);
    }

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
            if (itemDI != SimpleValue.NULL) {
                byte[] ipv4Bytes = toBytes(itemDI);
                try {
                    ipv4 = Inet4Address.getByAddress(ipv4Bytes).getHostAddress();
                } catch (Exception ex) {
                    log.error("Unable to convert byte[] to ipv4 address, {}, cbor: {}", ipv4Bytes,
                            HexUtil.encodeHexString(CborSerializationUtil.serialize(relayDI)), ex);
                }
            }

            //ipv6
            itemDI = relayItems.get(3);
            if (itemDI != SimpleValue.NULL) {
                byte[] ipv6Bytes = toBytes(itemDI);
                try {
                    ipv6 = Inet6Address.getByAddress(ipv6Bytes).getHostAddress();
                } catch (Exception ex) {
                    log.error("Unable to convert byte[] to ipv6 address, {}, cbor: {}", ipv6Bytes,
                            HexUtil.encodeHexString(CborSerializationUtil.serialize(relayDI)), ex);
                }
            }
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
}
