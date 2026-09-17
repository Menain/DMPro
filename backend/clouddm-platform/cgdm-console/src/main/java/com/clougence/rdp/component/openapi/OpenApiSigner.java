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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * @author bucketli 2021/10/11 19:47
 */
public class OpenApiSigner {

    public static final String  ENCODING       = "UTF-8";

    private static final String ALGORITHM_NAME = "HmacSHA1";

    public static String signString(String stringToSign, String accessKeySecret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM_NAME);
            mac.init(new SecretKeySpec(accessKeySecret.getBytes(ENCODING), ALGORITHM_NAME));
            byte[] signData = mac.doFinal(stringToSign.getBytes(ENCODING));
            return Base64.getEncoder().encodeToString(signData);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException | InvalidKeyException e) {
            throw new IllegalArgumentException(e.toString());
        }
    }

    public static String composeStringToSign(Map<String, String> queries) {
        try {
            String paramsStr = genSortedParamsStr(queries);
            return percentEncode(paramsStr);
        } catch (UnsupportedEncodingException exp) {
            throw new RuntimeException("UTF-8 encoding is not supported.");
        }
    }

    public static String genSortedParamsStr(Map<String, String> queries) {
        String[] sortedKeys = queries.keySet().toArray(new String[] {});
        Arrays.sort(sortedKeys);
        StringBuilder queryString = new StringBuilder();
        try {
            boolean first = true;
            for (String key : sortedKeys) {
                if (first) {
                    first = false;
                } else {
                    queryString.append("&");
                }

                queryString.append(percentEncode(key)).append("=").append(percentEncode(queries.get(key)));
            }

            return queryString.toString();
        } catch (UnsupportedEncodingException exp) {
            throw new RuntimeException("UTF-8 encoding is not supported.");
        }
    }

    private static String percentEncode(String value) throws UnsupportedEncodingException {
        return value != null ? URLEncoder.encode(value, ENCODING).replace("+", "%20").replace("*", "%2A").replace("%7E", "~") : null;
    }

    /**
     * Computes the SHA-256 hex (lowercase) digest of the request body. A {@code null}
     * body is treated as empty, so GET requests and empty POST bodies share the same
     * well-known digest value (SHA-256 of the empty string).
     *
     * <p>This is the {@code BodyDigest} parameter the client carries in the signature
     * set; the server recomputes it from the cached body to detect tampering.</p>
     */
    public static String digestBody(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = body != null ? md.digest(body) : md.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Returns {@code true} when the claimed body digest matches the actual cached body.
     * Both null/empty bodies resolve to the same SHA-256-of-empty digest, so an empty
     * request body is accepted as long as the client sent that canonical digest.
     */
    public static boolean bodyDigestMatches(String claimedDigest, byte[] actualBody) {
        if (claimedDigest == null) {
            return false;
        }
        return claimedDigest.equals(digestBody(actualBody));
    }

    /**
     * Returns {@code true} when the epoch-millis timestamp is within {@code maxSkewMillis}
     * of the server clock. A non-numeric timestamp is treated as stale (rejected).
     */
    public static boolean isTimestampFresh(String timestamp, long maxSkewMillis) {
        if (timestamp == null) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp.trim());
            long skew = Math.abs(System.currentTimeMillis() - ts);
            return skew <= maxSkewMillis;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Composes the canonical string-to-sign for the 5-parameter signature set
     * (AccessKeyId, SignatureMethod, SignatureNonce, Timestamp, BodyDigest). Convenience
     * wrapper around {@link #composeStringToSign(Map)} for callers that build the map inline.
     */
    public static String composeStringToSign(String accessKeyId, String signatureMethod, String signatureNonce, String timestamp, String bodyDigest) {
        Map<String, String> paramToSign = new java.util.HashMap<>();
        paramToSign.put("AccessKeyId", accessKeyId);
        paramToSign.put("SignatureMethod", signatureMethod);
        paramToSign.put("SignatureNonce", signatureNonce);
        paramToSign.put("Timestamp", timestamp);
        paramToSign.put("BodyDigest", bodyDigest);
        return composeStringToSign(paramToSign);
    }
}
