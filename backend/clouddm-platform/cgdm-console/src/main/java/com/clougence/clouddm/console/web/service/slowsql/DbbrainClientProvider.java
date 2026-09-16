package com.clougence.clouddm.console.web.service.slowsql;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.dbbrain.v20210527.DbbrainClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Caches {@link DbbrainClient} instances by region + credential fingerprint.
 * Protected methods allow unit tests to inject a mock client.
 */
@Slf4j
@Component
public class DbbrainClientProvider {

    private final ConcurrentHashMap<String, DbbrainClient> clientCache = new ConcurrentHashMap<>();

    public DbbrainClient getClient(String region, String secretId, String secretKey) {
        String cacheKey = region + "|" + secretId;
        return clientCache.computeIfAbsent(cacheKey, k -> createClient(region, secretId, secretKey));
    }

    protected DbbrainClient createClient(String region, String secretId, String secretKey) {
        Credential cred = new Credential(secretId, secretKey);
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("dbbrain.tencentcloudapi.com");
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new DbbrainClient(cred, region, clientProfile);
    }

    protected void clearCache() {
        clientCache.clear();
    }
}
