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
package com.clougence.clouddm.console.web.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.clougence.clouddm.base.metadata.ds.DataSourceConfig;
import com.clougence.clouddm.console.web.model.fo.datasource.DsConfigSubmitFO;
import com.clougence.clouddm.console.web.model.fo.user.AddSubAccountFO;
import com.clougence.utils.StringUtils;

/**
 * Credential sanitization for operation-audit payloads.
 *
 * <p>Audit objects handed to {@code RdpOpAuditService.logAndAddOperationAudit} are JSON-serialized into BOTH
 * {@code dm_mon_op_audit.log_info} and the application log — any credential carried by the object is persisted in
 * plaintext twice. Callers that pass request FOs (or maps) which may carry credentials must hand over a sanitized
 * copy produced here instead; the original object is never mutated, so the business chain keeps the real values.
 *
 * <p>Sensitive key inventory (code evidence, task 10-11-ds-audit-credential-sanitization):
 * <ul>
 * <li>Exact list — every ds-config key annotated {@code @ConfigDef(isSecret = true)} on {@link DataSourceConfig}:
 * password, sslCaData, sslCaPassword, sslClientCertData, sslClientKeyData, sslClientKeyPassword (plugin subclasses
 * define no additional secret config keys).</li>
 * <li>Name-pattern fallback — defense in depth for credential-like keys added later without the annotation
 * (password/passwd/pwd/secret/token/accessKey/privateKey/credential, matched on the key NAME only).</li>
 * </ul>
 *
 * <p>Red line: this utility must never write the original value to any log or exception message.
 */
public class AuditSanitizeUtils {

    /** Fixed mask replacing a credential value in audit payloads (same literal as the platform full-mask). */
    public static final String    CREDENTIAL_MASK         = "******";

    /** Secret ds-config keys, evidence: {@code @ConfigDef(isSecret = true)} on {@link DataSourceConfig}. */
    private static final Set<String> EXACT_SECRET_KEYS    = Set.of(
        DataSourceConfig.Fields.password,
        DataSourceConfig.Fields.sslCaData,
        DataSourceConfig.Fields.sslCaPassword,
        DataSourceConfig.Fields.sslClientCertData,
        DataSourceConfig.Fields.sslClientKeyData,
        DataSourceConfig.Fields.sslClientKeyPassword);

    /** Lowercase key-name fragments for the defense-in-depth fallback; matched against the key name, never the value. */
    private static final String[] SENSITIVE_KEY_FRAGMENTS = {
            "password", "passwd", "pwd", "secret", "token", "accesskey", "privatekey", "credential" };

    private AuditSanitizeUtils() {
    }

    /** Whether a config/field key denotes a credential: exact secret-key list first, then the name-pattern fallback. */
    public static boolean isSensitiveKey(String key) {
        if (StringUtils.isBlank(key)) {
            return false;
        }
        if (EXACT_SECRET_KEYS.contains(key)) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        for (String fragment : SENSITIVE_KEY_FRAGMENTS) {
            if (normalized.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** Mask a credential value; blank values are kept as-is (nothing to protect, and "not provided" stays visible). */
    public static String maskValue(String value) {
        return StringUtils.isBlank(value) ? value : CREDENTIAL_MASK;
    }

    /** Order-preserving copy of the map with sensitive-key values masked; null-safe. */
    public static Map<String, String> sanitizeConfigMap(Map<String, String> configMap) {
        if (configMap == null) {
            return null;
        }
        Map<String, String> sanitized = new LinkedHashMap<>(configMap.size());
        configMap.forEach((key, value) -> sanitized.put(key, isSensitiveKey(key) ? maskValue(value) : value));
        return sanitized;
    }

    /**
     * Audit-safe copy of a datasource config submit FO: identity/binding fields (dsId, dsType, clusterId, envId,
     * instanceDesc, driver) and non-sensitive config entries are kept for troubleshooting; credential entries of
     * {@code configMap} are masked.
     */
    public static DsConfigSubmitFO sanitizedDsConfigSubmit(DsConfigSubmitFO fo) {
        if (fo == null) {
            return null;
        }
        DsConfigSubmitFO copy = new DsConfigSubmitFO();
        copy.setDsId(fo.getDsId());
        copy.setDsType(fo.getDsType());
        copy.setClusterId(fo.getClusterId());
        copy.setEnvId(fo.getEnvId());
        copy.setInstanceDesc(fo.getInstanceDesc());
        copy.setDriver(fo.getDriver());
        copy.setConfigMap(sanitizeConfigMap(fo.getConfigMap()));
        return copy;
    }

    /**
     * C8 M3 (裁决 3 — the audit detail endpoint's sanitization兜底): mask credential values inside a
     * persisted {@code dm_mon_op_audit.log_info} JSON string BEFORE it is ever returned to a client.
     * The write path is sanitized since C1, but PRE-C1 stock rows may still carry plaintext — this
     * is the read-side second insurance (design D4 双保险).
     *
     * <p>Recursive traversal of the parsed JSON (Maps and Lists at any depth): a value under a
     * sensitive key ({@link #isSensitiveKey} — the C1 two-layer detection) is masked with
     * {@link #maskValue}; blank values stay as-is; every other key/value is preserved verbatim
     * (troubleshooting value kept). Unparsable input (e.g. a 65535-truncated tail) is returned
     * UNCHANGED — the method never throws and never fabricates content.
     */
    public static String sanitizeLogInfoJson(String logInfoJson) {
        if (StringUtils.isBlank(logInfoJson)) {
            return logInfoJson;
        }
        Object parsed;
        try {
            parsed = com.clougence.utils.JsonUtils.toObj(logInfoJson, Object.class);
        } catch (Exception e) { // JsonUtils is @SneakyThrows — a checked JsonParseException must be caught too
            return logInfoJson; // not JSON (or truncated) — nothing to traverse; pass through
        }
        if (parsed == null) {
            return logInfoJson;
        }
        try {
            return com.clougence.utils.JsonUtils.toJson(sanitizeNode(parsed));
        } catch (Exception e) { // JsonUtils is @SneakyThrows — a checked JsonParseException must be caught too
            return logInfoJson;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeNode(Object node) {
        if (node instanceof Map) {
            Map<String, Object> source = (Map<String, Object>) node;
            Map<String, Object> sanitized = new LinkedHashMap<>(source.size());
            source.forEach((key, value) -> sanitized.put(key, isSensitiveKey(key) ? maskAnyValue(value) : sanitizeNode(value)));
            return sanitized;
        }
        if (node instanceof List) {
            List<Object> source = (List<Object>) node;
            List<Object> sanitized = new java.util.ArrayList<>(source.size());
            for (Object element : source) {
                sanitized.add(sanitizeNode(element));
            }
            return sanitized;
        }
        return node;
    }

    /** a sensitive key masks ANY value shape (fail-closed): strings via maskValue, everything else becomes the mask. */
    private static Object maskAnyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return maskValue((String) value);
        }
        return CREDENTIAL_MASK;
    }

    /**
     * Audit-safe copy of a sub-account creation FO with the password field masked. Callers must pass the ORIGINAL fo
     * to the business chain first — by audit time its password has typically been decrypted in place, so the copy is
     * the only safe object to serialize.
     */
    public static AddSubAccountFO sanitizedAddSubAccount(AddSubAccountFO fo) {
        if (fo == null) {
            return null;
        }
        AddSubAccountFO copy = new AddSubAccountFO();
        copy.setUserName(fo.getUserName());
        copy.setAccount(fo.getAccount());
        copy.setRoleId(fo.getRoleId());
        copy.setPassword(maskValue(fo.getPassword()));
        copy.setEmail(fo.getEmail());
        copy.setPhone(fo.getPhone());
        copy.setDisable(fo.getDisable());
        copy.setLoginLocked(fo.getLoginLocked());
        copy.setAllowLocal(fo.getAllowLocal());
        return copy;
    }
}
