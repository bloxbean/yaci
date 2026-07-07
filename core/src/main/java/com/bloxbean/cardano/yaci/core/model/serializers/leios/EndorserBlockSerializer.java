package com.bloxbean.cardano.yaci.core.model.serializers.leios;

import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlock;
import com.bloxbean.cardano.yaci.core.model.leios.EndorserBlockTxRef;
import com.bloxbean.cardano.yaci.core.protocol.Serializer;
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil;
import com.bloxbean.cardano.yaci.core.util.HexUtil;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Decodes the Musashi Endorser Block map and the array-wrapped omap dialect used by newer Leios specs.
 */
public enum EndorserBlockSerializer implements Serializer<EndorserBlock> {
    INSTANCE;

    private static final long CBOR_TAG_OMAP = 258;

    @Override
    public EndorserBlock deserialize(byte[] bytes) {
        List<EndorserBlockTxRef> txRefs = readTxRefs(bytes);
        return EndorserBlock.builder()
                .txRefs(txRefs)
                .cbor(HexUtil.encodeHexString(bytes))
                .computedHash(HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(bytes)))
                .build();
    }

    @Override
    public EndorserBlock deserializeDI(DataItem di) {
        return deserialize(CborSerializationUtil.serialize(di, false));
    }

    private List<EndorserBlockTxRef> readTxRefs(byte[] bytes) {
        LeiosCborReader reader = new LeiosCborReader(bytes);
        List<Entry> entries;
        int majorType = reader.peek() >> 5;
        if (majorType == LeiosCborReader.MAJOR_TYPE_MAP) {
            entries = readMapEntries(reader);
            reader.requireEnd();
        } else if (majorType == LeiosCborReader.MAJOR_TYPE_TAG) {
            entries = readTaggedOmapEntries(reader);
            reader.requireEnd();
        } else if (majorType == LeiosCborReader.MAJOR_TYPE_ARRAY) {
            entries = readWrappedEntries(reader);
            reader.requireEnd();
        } else {
            throw new IllegalArgumentException("Endorser Block must be a CBOR map or [omap]");
        }

        Set<String> seenHashes = new HashSet<>();
        List<EndorserBlockTxRef> txRefs = new ArrayList<>();
        for (Entry entry : entries) {
            if (!(entry.key().dataItem() instanceof ByteString txHashBytes)
                    || txHashBytes.getBytes().length != 32) {
                throw new IllegalArgumentException("Endorser Block tx hash must be a 32-byte byte string");
            }
            if (!(entry.value().dataItem() instanceof UnsignedInteger txSizeValue)) {
                throw new IllegalArgumentException("Endorser Block tx size must be an unsigned integer");
            }

            String txHash = HexUtil.encodeHexString(txHashBytes.getBytes());
            if (!seenHashes.add(txHash)) {
                throw new IllegalArgumentException("Duplicate Endorser Block tx hash: " + txHash);
            }

            BigInteger txSize = txSizeValue.getValue();
            if (txSize.signum() < 0 || txSize.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
                throw new IllegalArgumentException("Endorser Block tx size must fit in signed long");
            }
            txRefs.add(EndorserBlockTxRef.builder()
                    .txHash(txHash)
                    .txSize(txSize.longValue())
                    .build());
        }

        return txRefs;
    }

    private List<Entry> readMapOrOmapEntries(LeiosCborReader reader) {
        int majorType = reader.peek() >> 5;
        if (majorType == LeiosCborReader.MAJOR_TYPE_MAP) {
            return readMapEntries(reader);
        }
        if (majorType == LeiosCborReader.MAJOR_TYPE_TAG) {
            return readTaggedOmapEntries(reader);
        }
        if (majorType == LeiosCborReader.MAJOR_TYPE_ARRAY) {
            return readOmapEntries(reader);
        }
        throw new IllegalArgumentException("Endorser Block wrapper must contain a map or ordered map array");
    }

    private List<Entry> readWrappedEntries(LeiosCborReader reader) {
        long wrapperSize = reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        if (wrapperSize == LeiosCborReader.INDEFINITE) {
            if (reader.nextIsBreak()) {
                throw new IllegalArgumentException("array-wrapped Endorser Block must contain one item");
            }
            List<Entry> entries = readMapOrOmapEntries(reader);
            if (!reader.nextIsBreak()) {
                throw new IllegalArgumentException("array-wrapped Endorser Block must contain exactly one item");
            }
            reader.readBreak();
            return entries;
        }

        if (wrapperSize != 1) {
            throw new IllegalArgumentException("array-wrapped Endorser Block must contain exactly one item");
        }
        return readMapOrOmapEntries(reader);
    }

    private List<Entry> readTaggedOmapEntries(LeiosCborReader reader) {
        long tag = reader.readLength(LeiosCborReader.MAJOR_TYPE_TAG);
        if (tag != CBOR_TAG_OMAP) {
            throw new IllegalArgumentException("Endorser Block ordered map must use CBOR tag 258");
        }
        return readOmapEntries(reader);
    }

    private List<Entry> readMapEntries(LeiosCborReader reader) {
        long mapSize = reader.readLength(LeiosCborReader.MAJOR_TYPE_MAP);
        List<Entry> entries = new ArrayList<>();
        if (mapSize == LeiosCborReader.INDEFINITE) {
            while (!reader.nextIsBreak()) {
                entries.add(new Entry(reader.readDataItem(), reader.readDataItem()));
            }
            reader.readBreak();
        } else {
            for (long i = 0; i < mapSize; i++) {
                entries.add(new Entry(reader.readDataItem(), reader.readDataItem()));
            }
        }
        return entries;
    }

    private List<Entry> readOmapEntries(LeiosCborReader reader) {
        long arraySize = reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        List<Entry> entries = new ArrayList<>();
        if (arraySize == LeiosCborReader.INDEFINITE) {
            while (!reader.nextIsBreak()) {
                entries.add(readOmapPair(reader));
            }
            reader.readBreak();
        } else {
            for (long i = 0; i < arraySize; i++) {
                entries.add(readOmapPair(reader));
            }
        }
        return entries;
    }

    private Entry readOmapPair(LeiosCborReader reader) {
        long pairSize = reader.readLength(LeiosCborReader.MAJOR_TYPE_ARRAY);
        if (pairSize != 2 && pairSize != LeiosCborReader.INDEFINITE) {
            throw new IllegalArgumentException("ordered map pair must contain exactly two items");
        }
        LeiosCborReader.DecodedItem key = reader.readDataItem();
        LeiosCborReader.DecodedItem value = reader.readDataItem();
        if (pairSize == LeiosCborReader.INDEFINITE) {
            if (!reader.nextIsBreak()) {
                throw new IllegalArgumentException("ordered map pair has more than two items");
            }
            reader.readBreak();
        }
        return new Entry(key, value);
    }

    private record Entry(LeiosCborReader.DecodedItem key, LeiosCborReader.DecodedItem value) {
    }
}
