/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.rdp.component.openapi;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * T12 — signature hardening (design §11 / Q6 hard-cut).
 *
 * <p>Tests the pure signer/validation logic at the unit level (JUnit4, no Spring context):
 * the 5-parameter signature set, body-digest verification, timestamp-skew check, and the
 * hard cut that rejects old 3-parameter signatures. The {@link OpenApiSessionManager}
 * interceptor delegates to these static helpers, so the validation contract is pinned here.</p>
 */
public class OpenApiSignerTest {

    private static final String TEST_SK     = "test-secret-key-1234";

    private static final String AK           = "AK_TEST_001";

    private static final String SIGN_METHOD = "HmacSHA1";

    private static final long   SKEW_MILLIS  = 5L * 60 * 1000;

    // Known SHA-256 of the empty string (null body and empty body share this digest).
    private static final String EMPTY_BODY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private String currentTimestamp() {
        return String.valueOf(System.currentTimeMillis());
    }

    private String signFiveParams(String nonce, String timestamp, String bodyDigest) {
        String toSign = OpenApiSigner.composeStringToSign(AK, SIGN_METHOD, nonce, timestamp, bodyDigest);
        return OpenApiSigner.signString(toSign, TEST_SK);
    }

    /** T12-a: all five params present and authentic → regenerated signature matches the client's. */
    @Test
    public void fiveParamsAuthentic_signatureMatches() {
        String nonce = "nonce-ok-001";
        String timestamp = currentTimestamp();
        String bodyDigest = OpenApiSigner.digestBody("{\"ticketId\":100}".getBytes(StandardCharsets.UTF_8));

        String clientSignature = signFiveParams(nonce, timestamp, bodyDigest);

        String toSign = OpenApiSigner.composeStringToSign(AK, SIGN_METHOD, nonce, timestamp, bodyDigest);
        String serverSignature = OpenApiSigner.signString(toSign, TEST_SK);

        assertEquals(clientSignature, serverSignature);
    }

    /**
     * T12-b: old 3-parameter signature (no Timestamp/BodyDigest) → the 3-param composed
     * string differs from the 5-param string, so the server's regenerated signature never
     * matches → reject (hard cut, no compatibility branch).
     */
    @Test
    public void oldThreeParamSignature_rejectedByHardCut() {
        String nonce = "nonce-legacy";
        String timestamp = currentTimestamp();
        String bodyDigest = EMPTY_BODY_DIGEST;

        // Client signs with the legacy 3-param set (no Timestamp/BodyDigest).
        Map<String, String> legacyParamToSign = new HashMap<>();
        legacyParamToSign.put("SignatureMethod", SIGN_METHOD);
        legacyParamToSign.put("SignatureNonce", nonce);
        legacyParamToSign.put("AccessKeyId", AK);
        String legacyString = OpenApiSigner.composeStringToSign(legacyParamToSign);
        String legacySignature = OpenApiSigner.signString(legacyString, TEST_SK);

        // Server recomputes with the mandatory 5-param set.
        String serverString = OpenApiSigner.composeStringToSign(AK, SIGN_METHOD, nonce, timestamp, bodyDigest);
        String serverSignature = OpenApiSigner.signString(serverString, TEST_SK);

        assertFalse("legacy 3-param signature must NOT match the 5-param server signature", legacySignature.equals(serverSignature));
    }

    /**
     * T12-c: timestamp skewed beyond the 5-minute window → isTimestampFresh returns false.
     * A 1-second safety buffer avoids a clock-advancement race between computing the
     * timestamp and the freshness check.
     */
    @Test
    public void timestampSkewBeyondWindow_rejected() {
        String now = currentTimestamp();
        long stale = System.currentTimeMillis() - SKEW_MILLIS - 1000; // 5min + 1s in the past
        String staleTs = String.valueOf(stale);
        long future = System.currentTimeMillis() + SKEW_MILLIS + 1000; // 5min + 1s in the future
        String futureTs = String.valueOf(future);

        assertTrue("current timestamp must be fresh", OpenApiSigner.isTimestampFresh(now, SKEW_MILLIS));
        assertFalse("stale timestamp must be rejected", OpenApiSigner.isTimestampFresh(staleTs, SKEW_MILLIS));
        assertFalse("future timestamp must be rejected", OpenApiSigner.isTimestampFresh(futureTs, SKEW_MILLIS));
    }

    /**
     * T12-d: body tampered after signing → the server-recomputed digest no longer matches
     * the signed (claimed) digest → bodyDigestMatches returns false.
     */
    @Test
    public void bodyTampered_digestMismatchRejected() {
        byte[] originalBody = "{\"ticketId\":100}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"ticketId\":999}".getBytes(StandardCharsets.UTF_8);

        String claimedDigest = OpenApiSigner.digestBody(originalBody);

        assertTrue(OpenApiSigner.bodyDigestMatches(claimedDigest, originalBody));
        assertFalse("tampered body must not match the original digest", OpenApiSigner.bodyDigestMatches(claimedDigest, tamperedBody));
    }

    /**
     * T12-e: a missing Timestamp or BodyDigest means the client only sent a subset of the
     * five required params. The interceptor rejects such requests at the COMMON_PARAMS_HAS_EMTPY
     * guard (param extraction). Here we pin that a 3-entry param map produces a different
     * string-to-sign than the full 5-entry map, so any signature computed over a subset is
     * structurally incompatible with the server's 5-param recomputation.
     */
    @Test
    public void missingTimestampOrBodyDigest_subsetStringDiffersFromFullSet() {
        String nonce = "nonce-subset";
        String timestamp = currentTimestamp();
        String bodyDigest = EMPTY_BODY_DIGEST;

        Map<String, String> full = new HashMap<>();
        full.put("AccessKeyId", AK);
        full.put("SignatureMethod", SIGN_METHOD);
        full.put("SignatureNonce", nonce);
        full.put("Timestamp", timestamp);
        full.put("BodyDigest", bodyDigest);

        Map<String, String> withoutTimestamp = new HashMap<>(full);
        withoutTimestamp.remove("Timestamp");

        Map<String, String> withoutBodyDigest = new HashMap<>(full);
        withoutBodyDigest.remove("BodyDigest");

        String fullString = OpenApiSigner.composeStringToSign(full);
        String noTimestampString = OpenApiSigner.composeStringToSign(withoutTimestamp);
        String noBodyDigestString = OpenApiSigner.composeStringToSign(withoutBodyDigest);

        assertFalse("missing Timestamp must change the string-to-sign", fullString.equals(noTimestampString));
        assertFalse("missing BodyDigest must change the string-to-sign", fullString.equals(noBodyDigestString));
    }

    /**
     * T12-f: empty-body convention — null and empty-byte-array bodies both resolve to the
     * canonical SHA-256-of-empty digest, so GET-style (no body) endpoints verify cleanly.
     */
    @Test
    public void emptyBody_nullAndEmptyArrayProduceSameCanonicalDigest() {
        String nullDigest = OpenApiSigner.digestBody(null);
        String emptyDigest = OpenApiSigner.digestBody(new byte[0]);

        assertEquals(EMPTY_BODY_DIGEST, nullDigest);
        assertEquals(EMPTY_BODY_DIGEST, emptyDigest);
        assertTrue(OpenApiSigner.bodyDigestMatches(EMPTY_BODY_DIGEST, null));
        assertTrue(OpenApiSigner.bodyDigestMatches(EMPTY_BODY_DIGEST, new byte[0]));
    }

    /** Non-numeric timestamp is treated as stale (rejected), never throws. */
    @Test
    public void nonNumericTimestamp_rejectedNotThrown() {
        assertFalse(OpenApiSigner.isTimestampFresh("not-a-number", SKEW_MILLIS));
        assertFalse(OpenApiSigner.isTimestampFresh(null, SKEW_MILLIS));
    }

    /** Null claimed digest is rejected by bodyDigestMatches. */
    @Test
    public void nullClaimedDigest_rejected() {
        assertFalse(OpenApiSigner.bodyDigestMatches(null, new byte[0]));
    }
}
