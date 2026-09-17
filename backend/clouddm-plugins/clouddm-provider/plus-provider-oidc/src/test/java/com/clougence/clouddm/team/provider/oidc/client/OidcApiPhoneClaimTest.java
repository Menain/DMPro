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
package com.clougence.clouddm.team.provider.oidc.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.Test;

import com.clougence.clouddm.sdk.service.config.UserData;

/**
 * Pins phone claim extraction in {@link OidcApi#fetchUserInfo(String)}.
 * <p>
 * Casdoor embeds its raw user object into tokens, so the phone number arrives as the
 * non-standard {@code phone} claim without any {@code phone_number}; standard providers
 * (and the OIDC spec) use {@code phone_number}. The extraction must prefer the standard
 * claim and fall back to casdoor's.
 */
public class OidcApiPhoneClaimTest {

    private static UserData decode(String payloadJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        OidcApi api = new OidcApi(null, "primaryUid", new OidcCfg());
        return api.fetchUserInfo(header + "." + payload + ".");
    }

    @Test
    public void casdoorNonStandardPhoneClaimIsUsedAsFallback() {
        UserData data = decode("{\"sub\":\"uid-1\",\"phone\":\"17757156784\"}");
        assertEquals("17757156784", data.getPhone());
    }

    @Test
    public void standardPhoneNumberClaimIsUsed() {
        UserData data = decode("{\"sub\":\"uid-1\",\"phone_number\":\"13800000000\"}");
        assertEquals("13800000000", data.getPhone());
    }

    @Test
    public void standardClaimWinsOverCasdoorFallback() {
        UserData data = decode("{\"sub\":\"uid-1\",\"phone_number\":\"13800000000\",\"phone\":\"17757156784\"}");
        assertEquals("13800000000", data.getPhone());
    }

    @Test
    public void missingPhoneClaimsYieldNull() {
        UserData data = decode("{\"sub\":\"uid-1\"}");
        assertNull(data.getPhone());
    }
}
