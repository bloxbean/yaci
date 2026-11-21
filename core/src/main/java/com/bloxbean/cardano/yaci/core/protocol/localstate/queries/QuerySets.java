package com.bloxbean.cardano.yaci.core.protocol.localstate.queries;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import com.bloxbean.cardano.yaci.core.model.Credential;
import com.bloxbean.cardano.yaci.core.model.certs.StakeCredType;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Query arguments that are sets. The node decodes a set as strictly ascending (cardano-binary decodeSet), and closes
 * the connection on elements out of order or repeated, so they are sorted and each is written once.
 */
final class QuerySets {
    /** A credential's order in Haskell (derived Ord): ScriptHashObj before KeyHashObj, then the hash bytes. */
    private static final Comparator<Credential> CREDENTIAL_ORDER = Comparator
            .<Credential>comparingInt(c -> c.getType() == StakeCredType.SCRIPTHASH ? 0 : 1)
            .thenComparing(c -> HexUtil.decodeHexString(c.getHash()), Arrays::compareUnsigned);

    private QuerySets() {
    }

    /** #6.258 set of pool ids (hex), in unsigned byte order. */
    static Array poolIds(Collection<String> poolIds) {
        TreeSet<byte[]> sorted = new TreeSet<>(Arrays::compareUnsigned);
        poolIds.forEach(poolId -> sorted.add(HexUtil.decodeHexString(poolId)));
        Array set = new Array();
        sorted.forEach(poolId -> set.add(new ByteString(poolId)));
        set.setTag(258);
        return set;
    }

    /** #6.258 set of credentials, scripts first, each kind in unsigned byte order. */
    static Array credentials(Collection<Credential> credentials) {
        TreeMap<Credential, Credential> sorted = new TreeMap<>(CREDENTIAL_ORDER);
        credentials.forEach(credential -> sorted.putIfAbsent(credential, credential));
        Array set = new Array();
        sorted.keySet().forEach(credential -> set.add(credential.serialize()));
        set.setTag(258);
        return set;
    }
}
