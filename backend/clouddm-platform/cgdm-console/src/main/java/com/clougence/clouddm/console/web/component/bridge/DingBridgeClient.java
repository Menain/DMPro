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
package com.clougence.clouddm.console.web.component.bridge;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.clougence.utils.ExceptionUtils;
import com.clougence.utils.JsonUtils;
import com.clougence.utils.StringUtils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Outbound HTTP client of the DingTalk approval bridge (task 10-09-dingtalk-approval-bridge,
 * design §3.3). OkHttp configuration mirrors the {@code CanalHttpClient} discipline: ONE
 * static pooled client (ConnectionPool 500 / 60s keep-alive), connect timeout fixed at 3s,
 * read/write timeout from the {@code dingBridgeTimeoutMs} KV — applied per call via
 * {@code newBuilder()} so the pool/dispatcher stay shared (the {@code OpenApiHttpClient}
 * per-call {@code new OkHttpClient()} anti-pattern is deliberately NOT copied).
 * <p>
 * Contract: <b>never-throw</b> — network errors, timeouts, non-2xx responses and malformed
 * bodies are all normalized into {@link BridgeFlowResult} (ok / flowId / duplicated / error /
 * retryable). Error classification: 4xx = contract error, NOT retryable (human intervention);
 * 5xx / IO / timeout = retryable (compensation sweep). Cancel calls treat 404 as idempotent
 * success (design §2.2). Auth is the single-point {@code X-Bridge-Auth} header assembly.
 */
@Slf4j
@Component
public class DingBridgeClient {

    static final MediaType    JSON       = MediaType.get("application/json; charset=utf-8");
    static final String       AUTH_HEADER = "X-Bridge-Auth";
    static final String       PATH_CREATE = "/flows/create";
    static final String       PATH_CANCEL = "/flows/cancel";

    /** fixed connect timeout (design §3.2 — only the read/write timeout is KV-driven). */
    public static final long  CONNECT_TIMEOUT_MS   = 3_000L;
    private static final int  CONNECTION_POOL_SIZE = 500;
    private static final long KEEP_ALIVE_MILLIS    = 60_000L;

    /** the single pooled client — per-call timeouts derive from it via newBuilder (shared pool). */
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(10_000L, TimeUnit.MILLISECONDS)
        .writeTimeout(10_000L, TimeUnit.MILLISECONDS)
        .connectionPool(new ConnectionPool(CONNECTION_POOL_SIZE, KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS))
        .build();

    /**
     * POST {baseUrl}/flows/create — initiate the DingTalk approval instance through the bridge.
     * Never throws.
     */
    public BridgeFlowResult createFlow(BridgeEndpoint endpoint, BridgeCreateRequest request) {
        return post(endpoint, PATH_CREATE, JsonUtils.toJson(request), false);
    }

    /**
     * POST {baseUrl}/flows/cancel — best-effort revoke notification. Never throws;
     * HTTP 404 counts as success (the instance is already gone — idempotent, design §2.2).
     */
    public BridgeFlowResult cancelFlow(BridgeEndpoint endpoint, BridgeCancelRequest request) {
        return post(endpoint, PATH_CANCEL, JsonUtils.toJson(request), true);
    }

    private BridgeFlowResult post(BridgeEndpoint endpoint, String path, String json, boolean notFoundIsOk) {
        if (endpoint == null || StringUtils.isBlank(endpoint.getBaseUrl())) {
            return BridgeFlowResult.failure("bridge baseUrl not configured", false);
        }
        String url = joinUrl(endpoint.getBaseUrl(), path);
        Response response = null;
        try {
            Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON, json));
            // single-point auth header assembly (blank token = header omitted, bridge decides)
            if (StringUtils.isNotBlank(endpoint.getAuthToken())) {
                builder.header(AUTH_HEADER, endpoint.getAuthToken());
            }
            OkHttpClient client = BASE_CLIENT.newBuilder()
                .readTimeout(endpoint.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(endpoint.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
            response = client.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() != null ? response.body().string() : "";
            return toFlowResult(code, body, notFoundIsOk);
        } catch (Throwable t) {
            // IO / timeout / interrupt — all retryable (transient by classification, design §9 #2)
            String msg = ExceptionUtils.getRootCauseMessage(t);
            log.warn("[DingBridge] outbound call to {} failed: {}", url, msg);
            return BridgeFlowResult.failure(msg, true);
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * Response classification — the single point mapping (HTTP status, body) onto
     * {@link BridgeFlowResult}. Package-visible for unit tests (zero real network, 红线).
     */
    static BridgeFlowResult toFlowResult(int httpCode, String body, boolean notFoundIsOk) {
        if (httpCode == 404 && notFoundIsOk) {
            // cancel idempotency: the instance is already gone = success
            return BridgeFlowResult.ok(null, false);
        }
        if (httpCode >= 200 && httpCode < 300) {
            try {
                BridgeCreateResponse resp = JsonUtils.toObj(body, BridgeCreateResponse.class);
                if (resp == null) {
                    return BridgeFlowResult.failure("malformed bridge response (empty body)", false);
                }
                return BridgeFlowResult.ok(resp.getFlowId(), Boolean.TRUE.equals(resp.getDuplicated()));
            } catch (Throwable t) {
                // 2xx with an unparsable body = contract error, not retryable
                return BridgeFlowResult.failure("malformed bridge response: " + ExceptionUtils.getRootCauseMessage(t), false);
            }
        }
        String error = "HTTP " + httpCode + (StringUtils.isBlank(body) ? "" : ": " + StringUtils.substring(body, 0, 300));
        // 4xx = contract error (human intervention); 5xx and anything else = retryable
        return BridgeFlowResult.failure(error, httpCode >= 500);
    }

    /** trailing-slash tolerant URL join. */
    static String joinUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }
}
