package com.bloxbean.cardano.yaci.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0003 D4b: the types Jackson serializes reflectively for D4b's fields are registered in Yaci's native-image
 * metadata, with their constructors, fields and methods: the six new models, and {@link Credential}, which the guard
 * native script's JSON writes.
 */
class NativeImageMetadataTest {
    private static final String METADATA = "/META-INF/native-image/yaci/reflect-config.json";

    @Test
    void d4bTypesAreRegisteredForReflection() throws Exception {
        Map<String, JsonNode> entries = new HashMap<>();
        try (InputStream in = NativeImageMetadataTest.class.getResourceAsStream(METADATA)) {
            for (JsonNode entry : new ObjectMapper().readTree(in))
                entries.put(entry.get("name").asText(), entry);
        }
        for (Class<?> type : List.of(Credential.class, SubTransaction.class, TopLevelGuard.class,
                AccountBalanceInterval.class, BlsKey.class, LeiosCertificate.class, EbAnnouncement.class)) {
            JsonNode entry = entries.get(type.getName());
            assertThat(entry).as(type.getName()).isNotNull();
            for (String flag : List.of("allDeclaredConstructors", "allDeclaredFields", "allDeclaredMethods"))
                assertThat(entry.path(flag).asBoolean()).as(type.getName() + " " + flag).isTrue();
        }
        for (String name : entries.keySet())
            if (name.startsWith("com.bloxbean."))
                assertThat(Class.forName(name, false, getClass().getClassLoader())).as(name).isNotNull();
    }
}
