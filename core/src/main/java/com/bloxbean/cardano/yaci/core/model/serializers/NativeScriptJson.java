package com.bloxbean.cardano.yaci.core.model.serializers;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Special;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.exception.CborRuntimeException;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeAfter;
import com.bloxbean.cardano.client.transaction.spec.script.RequireTimeBefore;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.yaci.core.model.NativeScript;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import static com.bloxbean.cardano.yaci.core.model.NativeScriptType.*;

/**
 * Converts the native-script CBOR tree into the existing client-library JSON representation.
 *
 * <p>Composite scripts are written with an explicit stack of child iterators. Each iterator
 * represents an open JSON object and its {@code scripts} array; exhausting it closes both.
 * This avoids recursive Script objects and recursive Jackson object serialization. Leaf scripts
 * still use the client library so their field names and numeric conversions remain unchanged.
 * A bounded iterative size estimate selects legacy pretty printing for small scripts and
 * compact JSON for large scripts, avoiding excessive indentation before allocating the output.
 *
 * <p>JSON I/O failures produce a {@code parseError}; malformed script structure still throws.
 */
final class NativeScriptJson {
    private static final int TYPE_INDEX = 0;
    private static final int CHILDREN_INDEX = 1;
    private static final int KEY_HASH_INDEX = 1;
    private static final int REQUIRED_COUNT_INDEX = 1;
    private static final int M_OF_CHILDREN_INDEX = 2;
    // Estimated character budget for pretty printing, not a script size or nesting limit.
    private static final int MAX_PRETTY_JSON_SIZE = 64 * 1024;

    // This factory is private to the iterative writer, not a global Jackson constraint override.
    private static final JsonFactory FACTORY = JsonFactory.builder()
            .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(Integer.MAX_VALUE).build())
            .build();
    private static final ObjectMapper LEAF_MAPPER = new ObjectMapper();

    private NativeScriptJson() {}

    /**
     * Converts one script, preserving the existing omission behavior for unknown types.
     *
     * @param script decoded CBOR array beginning with a native-script discriminator
     * @return JSON or an explicit representation failure; null for an unknown script type
     * @throws CborRuntimeException if the script structure cannot be interpreted
     */
    static NativeScript parse(Array script) {
        return parse(script, FACTORY);
    }

    /** Allows tests to exercise JSON failure handling with a constrained, local factory. */
    static NativeScript parse(Array script, JsonFactory factory) {
        int type = type(script);
        if (type > REQUIRE_TIME_BEFORE) {
            return null; // Preserve the existing behavior for unknown native-script types.
        }
        StringWriter output = new StringWriter();
        try (JsonGenerator json = factory.createGenerator(output)) {
            if (shouldPrettyPrint(script)) {
                json.setPrettyPrinter(new DefaultPrettyPrinter());
            }
            Deque<Iterator<DataItem>> parents = new ArrayDeque<>();
            Array next = script;
            while (true) {
                writeScript(next, json, parents);
                next = null;
                while (!parents.isEmpty()) {
                    Iterator<DataItem> children = parents.peek();
                    if (!children.hasNext()) {
                        parents.pop();
                        json.writeEndArray();
                        json.writeEndObject();
                    } else {
                        DataItem child = children.next();
                        if (Special.BREAK.equals(child)) {
                            continue;
                        }
                        if (!(child instanceof Array)) {
                            throw new CborRuntimeException("Native script child must be an array");
                        }
                        next = (Array) child;
                        break;
                    }
                }
                if (next == null) {
                    break;
                }
            }
        } catch (IOException e) {
            // Only JSON representation failures are isolated. Invalid script structure propagates.
            // Do not format the script or recursive exception contents, or log once per script.
            return new NativeScript(type, null, "Native script JSON conversion failed: "
                    + e.getClass().getSimpleName());
        }
        return new NativeScript(type, output.toString());
    }

    /**
     * Estimates pretty-printed characters without creating JSON or recursive script objects.
     * Each object adds field text and three indented lines (four for atLeast). The fixed
     * allowances below conservatively cover punctuation, field names, numbers and line endings.
     * Stop as soon as the budget is exceeded; the writer still validates and emits every node.
     */
    private static boolean shouldPrettyPrint(Array script) {
        long estimatedSize = 0;
        Deque<Iterator<DataItem>> parents = new ArrayDeque<>();
        Array next = script;
        try {
            while (true) {
                int type = type(next);
                if (type <= REQUIRE_TIME_BEFORE) {
                    List<DataItem> items = next.getDataItems();
                    int depth = parents.size();
                    // Two spaces per indentation level on each of the object's lines.
                    estimatedSize += 64L + 6L * depth;
                    if (type == REQUIRE_SIGNATURE) {
                        estimatedSize += 2L * ((ByteString) items.get(KEY_HASH_INDEX)).getBytes().length;
                    } else if (type == REQUIRE_M_OF) {
                        estimatedSize += 24L + 2L * depth
                                + ((co.nstant.in.cbor.model.Number) items.get(REQUIRED_COUNT_INDEX))
                                .getValue().toString().length();
                    }
                    if (estimatedSize > MAX_PRETTY_JSON_SIZE) {
                        return false;
                    }
                    if (type == REQUIRE_ALL_OF || type == REQUIRE_ANY_OF || type == REQUIRE_M_OF) {
                        Array children = (Array) items.get(type == REQUIRE_M_OF
                                ? M_OF_CHILDREN_INDEX : CHILDREN_INDEX);
                        parents.push(children.getDataItems().iterator());
                    }
                }
                next = null;
                while (!parents.isEmpty() && next == null) {
                    Iterator<DataItem> children = parents.peek();
                    if (!children.hasNext()) {
                        parents.pop();
                    } else {
                        DataItem child = children.next();
                        if (!Special.BREAK.equals(child)) {
                            next = (Array) child;
                        }
                    }
                }
                if (next == null) {
                    return true;
                }
            }
        } catch (CborRuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("Invalid native script structure", e);
        }
    }

    /**
     * Writes a leaf completely, or opens a composite and pushes its children for the main loop.
     * This method never calls itself; the caller closes each composite after its children finish.
     */
    private static void writeScript(Array script, JsonGenerator json, Deque<Iterator<DataItem>> parents)
            throws IOException {
        int type = type(script);
        if (type > REQUIRE_TIME_BEFORE) {
            return;
        }
        List<DataItem> items = script.getDataItems();
        try {
            if (type == REQUIRE_SIGNATURE || type == REQUIRE_TIME_AFTER || type == REQUIRE_TIME_BEFORE) {
                // Leaf parsers retain the client library's existing field and numeric semantics.
                Object leaf = type == REQUIRE_SIGNATURE ? ScriptPubkey.deserialize(script)
                        : type == REQUIRE_TIME_AFTER ? RequireTimeAfter.deserialize(script)
                        : RequireTimeBefore.deserialize(script);
                LEAF_MAPPER.writeValue(json, leaf);
                return;
            }
            DataItem children = items.get(type == REQUIRE_M_OF ? M_OF_CHILDREN_INDEX : CHILDREN_INDEX);
            if (!(children instanceof Array)) {
                throw new CborRuntimeException("Native script children must be an array");
            }
            json.writeStartObject();
            json.writeStringField("type", type == REQUIRE_ALL_OF ? "all" : type == REQUIRE_ANY_OF ? "any" : "atLeast");
            if (type == REQUIRE_M_OF) {
                json.writeFieldName("required");
                json.writeNumber(((co.nstant.in.cbor.model.Number) items.get(REQUIRED_COUNT_INDEX)).getValue());
            }
            json.writeArrayFieldStart("scripts");
            parents.push(((Array) children).getDataItems().iterator());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new CborRuntimeException("Invalid native script structure", e);
        }
    }

    /** Reads the discriminator without converting malformed or oversized values into a fallback script. */
    private static int type(Array script) {
        List<DataItem> items = script.getDataItems();
        if (items.isEmpty() || !(items.get(TYPE_INDEX) instanceof UnsignedInteger)) {
            throw new CborRuntimeException("Native script must start with an unsigned integer type");
        }
        try {
            return ((UnsignedInteger) items.get(TYPE_INDEX)).getValue().intValueExact();
        } catch (ArithmeticException e) {
            throw new CborRuntimeException("Native script type is out of range", e);
        }
    }
}
