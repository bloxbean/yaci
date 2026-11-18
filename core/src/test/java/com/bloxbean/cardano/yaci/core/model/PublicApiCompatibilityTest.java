package com.bloxbean.cardano.yaci.core.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0003 I16, as ADR 0004 W2: every public constructor, method and field of the public classes of Yaci's model and
 * serializer packages at the M7a commit (51e7870), which hold every class M7c changes, still exists with the same
 * signature. The listing was read by reflection from that commit's compiled classes, rendered as
 * {@link #signatures(Class)} renders them now.
 */
class PublicApiCompatibilityTest {
    private static final String LISTING = "/compat/m7c-public-api.txt";

    @Test
    void everyPublicSignatureBeforeM7cStillExists() throws Exception {
        List<String> before = listing();
        Set<String> now = new TreeSet<>();
        for (String type : before.stream().map(line -> line.substring(0, line.indexOf('#'))).distinct()
                .collect(Collectors.toList()))
            now.addAll(signatures(Class.forName(type)));

        assertThat(before).hasSizeGreaterThan(1000);
        assertThat(before).anyMatch(line -> line.startsWith(TransactionBody.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(Witnesses.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(AuxData.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(PoolParams.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(ProtocolParamUpdate.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(Block.class.getName() + "#<init>("))
                .anyMatch(line -> line.startsWith(HeaderBody.class.getName() + "#<init>("));
        assertThat(before.stream().filter(signature -> !now.contains(signature)).collect(Collectors.toList()))
                .as("public signatures that changed or disappeared").isEmpty();
    }

    private static Set<String> signatures(Class<?> type) {
        Set<String> signatures = new TreeSet<>();
        for (Constructor<?> constructor : type.getConstructors())
            signatures.add(type.getName() + "#<init>(" + parameters(constructor.getParameterTypes()) + ")");
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class || method.isSynthetic() || method.isBridge())
                continue;
            signatures.add(type.getName() + "#" + (Modifier.isStatic(method.getModifiers()) ? "static " : "")
                    + method.getName() + "(" + parameters(method.getParameterTypes()) + ")"
                    + method.getReturnType().getTypeName());
        }
        for (Field field : type.getFields())
            signatures.add(type.getName() + "#field " + (Modifier.isStatic(field.getModifiers()) ? "static " : "")
                    + field.getName() + ":" + field.getType().getTypeName());
        return signatures;
    }

    private static String parameters(Class<?>[] types) {
        return Arrays.stream(types).map(Class::getTypeName).collect(Collectors.joining(","));
    }

    private static List<String> listing() throws IOException {
        try (InputStream in = PublicApiCompatibilityTest.class.getResourceAsStream(LISTING)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).collect(Collectors.toList());
        }
    }
}
