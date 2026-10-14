package com.clougence.clouddm.console.web.model.fo;

import java.util.Date;

import com.clougence.clouddm.platform.dal.model.monitor.SecurityLevel;

import lombok.Data;

/**
 * @author bucketli 2021/2/1 21:26
 */
@Data
public class QueryOpAuditFO {

    private Date          opStart;

    private Date          opEnd;

    private SecurityLevel securityLevel;

    private String        auditType;

    private String        resourceType;

    private String        userNameLike;

    private PageData      pageData;

    private String        uid;

    // ---- C8 M2 additive audit-chain filters (design D4; all optional — null = no predicate) ----
    /** AuditChannel enum name: CONSOLE / OPENAPI / SYSTEM_AUTO. */
    private String        channel;
    private Long          ticketId;
    private Long          releaseId;
    private String        sqlFingerprint;
}
