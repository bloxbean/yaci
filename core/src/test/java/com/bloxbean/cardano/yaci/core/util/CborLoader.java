package com.bloxbean.cardano.yaci.core.util;

import co.nstant.in.cbor.model.DataItem;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;

@Slf4j
@UtilityClass
public class CborLoader {

    public static DataItem getDataItem(String path) {
        return CborSerializationUtil.deserializeOne(getHexBytes(path));
    }

    public static byte[] getHexBytes(String path) {
        StringBuilder content = new StringBuilder();
        var resource = CborLoader.class.getClassLoader().getResourceAsStream(path);
        try (BufferedInputStream bufferedInputStream = new BufferedInputStream(resource)) {
            byte[] bytes = new byte[500];
            while (bufferedInputStream.available() != 0) {

                content.append(new String(bufferedInputStream.readAllBytes()));
            }
        } catch (IOException exception) {
            log.error(exception.getMessage());
        }

        return HexUtil.decodeHexString(content.toString());
    }
}
