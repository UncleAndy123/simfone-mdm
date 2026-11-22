package com.simfone.mdm.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class FallbackManifestTest {
    private static final long NOW = 1_800_000_000L;

    private static JSONObject payload() throws Exception {
        return new JSONObject().put("v", 1).put("action", "release").put("nonce", "0123456789abcdef")
                .put("notBefore", NOW - 10).put("expires", NOW + 100)
                .put("targets", new JSONArray().put("DEV-1"));
    }

    private static final Set<String> NONE = Collections.emptySet();

    @Test public void validTargeted() throws Exception {
        assertNull(FallbackManifest.checkPayload(payload(), "DEV-1", NOW, NONE));
    }

    @Test public void wildcard() throws Exception {
        assertNull(FallbackManifest.checkPayload(payload().put("targets", "*"), "X", NOW, NONE));
    }

    @Test public void notTargeted() throws Exception {
        assertEquals("not targeted", FallbackManifest.checkPayload(payload(), "DEV-2", NOW, NONE));
    }

    @Test public void expiredAndEarly() throws Exception {
        assertEquals("expired", FallbackManifest.checkPayload(payload(), "DEV-1", NOW + 1000, NONE));
        assertEquals("not yet valid", FallbackManifest.checkPayload(payload(), "DEV-1", NOW - 1000, NONE));
    }

    @Test public void replayRejected() throws Exception {
        Set<String> used = new HashSet<>(Collections.singleton("0123456789abcdef"));
        assertEquals("nonce already used", FallbackManifest.checkPayload(payload(), "DEV-1", NOW, used));
    }

    @Test public void newServerMustBeHttps() throws Exception {
        JSONObject p = payload().put("action", "newServerUrl").put("newServerUrl", "http://evil");
        assertEquals("newServerUrl must be https", FallbackManifest.checkPayload(p, "DEV-1", NOW, NONE));
    }
}
