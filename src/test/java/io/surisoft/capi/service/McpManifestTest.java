package io.surisoft.capi.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class McpManifestTest {

    @Test
    void canonicalForm_isStable() {
        byte[] a = McpManifest.canonicalize("svc-1", "doThing", "Does the thing",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", "1");
        byte[] b = McpManifest.canonicalize("svc-1", "doThing", "Does the thing",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", "1");
        assertArrayEquals(a, b);
    }

    @Test
    void inputSchemaKeyOrder_doesNotAffectOutput() {
        byte[] a = McpManifest.canonicalize("svc-1", "doThing", "d",
                "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}", "1");
        byte[] b = McpManifest.canonicalize("svc-1", "doThing", "d",
                "{\"properties\":{\"x\":{\"type\":\"string\"}},\"type\":\"object\"}", "1");
        assertArrayEquals(a, b);
    }

    @Test
    void inputSchemaWhitespace_doesNotAffectOutput() {
        byte[] a = McpManifest.canonicalize("svc", "t", "d",
                "{\"type\":\"object\"}", "1");
        byte[] b = McpManifest.canonicalize("svc", "t", "d",
                "  {  \"type\" : \"object\"   }  ", "1");
        assertArrayEquals(a, b);
    }

    @Test
    void topLevelOutput_hasSortedKeys() {
        String s = McpManifest.canonicalizeAsString("svc-1", "doThing", "Hi",
                "{\"type\":\"object\"}", "1");
        // No whitespace, top-level keys in alphabetical order
        assertEquals(
                "{\"description\":\"Hi\",\"inputSchema\":{\"type\":\"object\"},\"name\":\"doThing\",\"serviceId\":\"svc-1\",\"version\":\"1\"}",
                s);
    }

    @Test
    void differingDescription_changesBytes() {
        byte[] a = McpManifest.canonicalize("svc", "t", "Original description", "{\"type\":\"object\"}", "1");
        byte[] b = McpManifest.canonicalize("svc", "t", "Tampered description", "{\"type\":\"object\"}", "1");
        assertNotEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void differingVersion_changesBytes() {
        byte[] a = McpManifest.canonicalize("svc", "t", "d", "{\"type\":\"object\"}", "1");
        byte[] b = McpManifest.canonicalize("svc", "t", "d", "{\"type\":\"object\"}", "2");
        assertNotEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }

    @Test
    void nullVersion_isCanonicalisedAsEmpty() {
        byte[] a = McpManifest.canonicalize("svc", "t", "d", "{\"type\":\"object\"}", null);
        byte[] b = McpManifest.canonicalize("svc", "t", "d", "{\"type\":\"object\"}", "");
        assertArrayEquals(a, b);
    }

    @Test
    void blankInputSchema_fallsBackToObject() {
        byte[] withBlank = McpManifest.canonicalize("svc", "t", "d", "", "1");
        byte[] withDefault = McpManifest.canonicalize("svc", "t", "d", "{\"type\":\"object\"}", "1");
        assertArrayEquals(withBlank, withDefault);
    }

    @Test
    void deeplyNestedKeys_areSortedAtEveryDepth() {
        byte[] a = McpManifest.canonicalize("svc", "t", "d",
                "{\"properties\":{\"foo\":{\"description\":\"x\",\"type\":\"string\"}},\"type\":\"object\"}", "1");
        byte[] b = McpManifest.canonicalize("svc", "t", "d",
                "{\"type\":\"object\",\"properties\":{\"foo\":{\"type\":\"string\",\"description\":\"x\"}}}", "1");
        assertArrayEquals(a, b);
    }

    @Test
    void arrayOrder_isPreserved() {
        // required is semantically order-insensitive but JSON arrays carry order.
        // Operators must use a stable order; the canonicaliser should not silently reorder.
        byte[] a = McpManifest.canonicalize("svc", "t", "d",
                "{\"type\":\"object\",\"required\":[\"a\",\"b\"]}", "1");
        byte[] b = McpManifest.canonicalize("svc", "t", "d",
                "{\"type\":\"object\",\"required\":[\"b\",\"a\"]}", "1");
        assertNotEquals(new String(a, StandardCharsets.UTF_8), new String(b, StandardCharsets.UTF_8));
    }
}