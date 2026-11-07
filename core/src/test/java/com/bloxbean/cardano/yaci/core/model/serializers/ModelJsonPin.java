package com.bloxbean.cardano.yaci.core.model.serializers;

import com.bloxbean.cardano.client.spec.Era;
import com.bloxbean.cardano.yaci.core.config.YaciConfig;
import com.bloxbean.cardano.yaci.core.util.CborLoader;
import com.bloxbean.cardano.yaci.core.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR 0003 I16: the models of Yaci's fixtures as JSON, written with a bare {@link ObjectMapper} as Yaci and Yaci Store
 * write them. An entry is {@code <flags>:<resource>[#line]}: a block, the witness set fixture, or a governance body
 * with its era, read with the CBOR flags of {@link YaciConfig} off ({@code plain}) or on ({@code cbor}).
 */
final class ModelJsonPin {
    static final ObjectMapper JSON = new ObjectMapper();

    private ModelJsonPin() {
    }

    static String json(String entry) throws IOException {
        String flags = entry.substring(0, entry.indexOf(':'));
        String resource = entry.substring(flags.length() + 1);
        YaciConfig config = YaciConfig.INSTANCE;
        boolean block = config.isReturnBlockCbor();
        boolean body = config.isReturnTxBodyCbor();
        boolean fullTx = config.isReturnFullTxCbor();
        boolean cbor = flags.equals("cbor");
        try {
            config.setReturnBlockCbor(cbor);
            config.setReturnTxBodyCbor(cbor);
            config.setReturnFullTxCbor(cbor);
            return JSON.writeValueAsString(model(resource));
        } finally {
            config.setReturnBlockCbor(block);
            config.setReturnTxBodyCbor(body);
            config.setReturnFullTxCbor(fullTx);
        }
    }

    private static Object model(String resource) throws IOException {
        if (resource.startsWith("governance/")) {
            String[] parts = lines("/" + resource.substring(0, resource.indexOf('#')))
                    .get(Integer.parseInt(resource.substring(resource.indexOf('#') + 1))).split(",");
            return TransactionBodySerializer.INSTANCE.read(HexUtil.decodeHexString(parts[1]), Era.valueOf(parts[0]));
        }
        byte[] bytes = CborLoader.getHexBytes(resource);
        if (resource.endsWith("/witness-preprod-f90dce57.txt"))
            return WitnessesSerializer.INSTANCE.deserialize(bytes);
        if (resource.endsWith("/byron-main.txt"))
            return ByronBlockSerializer.INSTANCE.deserialize(bytes);
        return BlockSerializer.INSTANCE.deserialize(enveloped(bytes));
    }

    /** A block in its [era, block] envelope; a Dijkstra block recorded without one gets index 8. */
    static byte[] enveloped(byte[] bytes) {
        if (bytes[0] == (byte) 0x82 && (bytes[1] & 0xff) <= 0x17)
            return bytes;
        return HexUtil.decodeHexString("8208" + HexUtil.encodeHexString(bytes));
    }

    /** The lines of a resource, without blank lines and {@code #} comments. */
    static List<String> lines(String resource) throws IOException {
        try (InputStream in = ModelJsonPin.class.getResourceAsStream(resource)) {
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
                if (!line.isBlank() && !line.startsWith("#"))
                    lines.add(line);
            return lines;
        }
    }
}
