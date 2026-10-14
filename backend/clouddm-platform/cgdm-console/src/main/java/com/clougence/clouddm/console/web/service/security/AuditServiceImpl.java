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
package com.clougence.clouddm.console.web.service.security;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.clougence.clouddm.api.common.boot.UnifiedPostConstruct;
import com.clougence.clouddm.api.console.sqlaudit.SqlExecNotifyDTO;
import com.clougence.clouddm.api.console.sqlaudit.SqlStatus;
import com.clougence.clouddm.api.console.sqlaudit.Type;
import com.clougence.clouddm.console.web.component.config.RootUserConfig;
import com.clougence.clouddm.console.web.global.notify.DmWorkerRegisterNotify;
import com.clougence.clouddm.console.web.service.auth.RdpUserService;
import com.clougence.clouddm.console.web.util.DbErrorSanitizer;
import com.clougence.clouddm.console.web.util.RdpHostUtil;
import com.clougence.clouddm.platform.dal.access.DataSourceDal;
import com.clougence.clouddm.platform.dal.access.ExecutionDal;
import com.clougence.clouddm.platform.dal.access.MonitorDal;
import com.clougence.clouddm.platform.dal.access.SystemDal;
import com.clougence.clouddm.platform.dal.model.auth.DmAuthUserDO;
import com.clougence.clouddm.platform.dal.model.datasource.DmDsDO;
import com.clougence.clouddm.platform.dal.model.execution.DmExecSqlAuditDO;
import com.clougence.clouddm.platform.dal.model.system.DmSysUserConfDO;
import com.clougence.clouddm.sdk.execute.session.QueryRequest;
import com.clougence.clouddm.sdk.service.secrules.Requester;
import com.clougence.utils.StringUtils;
import com.clougence.utils.ThreadUtils;

import jakarta.annotation.Resource;
import lombok.Setter;

/**
 * @author mode 2020-01-20 21:04
 * @since 1.1.3
 */
@Service
public class AuditServiceImpl implements AuditService, DmWorkerRegisterNotify, UnifiedPostConstruct {

    private final Logger                logger = LoggerFactory.getLogger("sql-audit");
    private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;
    private final AtomicBoolean         inited = new AtomicBoolean();
    @Resource
    private SystemDal                   systemDal;
    @Resource
    private ExecutionDal                execDal;
    @Resource
    private DataSourceDal               dsDal;
    @Resource
    private MonitorDal                  monitorDal;
    @Resource
    private RdpUserService              userService;

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void prepareAudit(Long dsId, String auditUid, QueryRequest request) {
        if (request == null) {
            return;
        }
        DmExecSqlAuditDO exists = this.execDal.sqlAuditMapper().queryByQueryId(request.getQueryId());
        if (exists != null && request.getRequester() == Requester.CONSOLE) {
            throw new IllegalStateException("Duplicate SQL audit ACK: " + request.getQueryId());
        }
        if (exists != null) {
            return;
        }
        String userName = auditUid;
        if (StringUtils.isNotBlank(auditUid)) {
            DmAuthUserDO user = this.userService.getUserByUid(auditUid);
            if (user != null) {
                if (StringUtils.isNotBlank(user.getUsername())) {
                    userName = user.getUsername();
                } else if (StringUtils.isNotBlank(user.getAccount())) {
                    userName = user.getAccount();
                } else if (StringUtils.isNotBlank(user.getBindAccount())) {
                    userName = user.getBindAccount();
                }
            }
        }
        DmDsDO dsDO = this.dsDal.dsMapper().queryDsIdentityById(dsId);

        DmExecSqlAuditDO auditDO = new DmExecSqlAuditDO();
        auditDO.setQueryId(request.getQueryId());
        auditDO.setBehaviors(request.getRelations());
        auditDO.setExecSql(getString(request.getQueryBody()));
        auditDO.setOriginalSql(request.isHasRewrite() ? getString(request.getOriginalBody()) : null);
        // C8 M2 (design D3): sql_fingerprint = GovSqlHashUtils over the FULL statement text
        // (pre-truncation — the governance stmt_hash join caliber; the 65535-truncated exec_sql
        // column value could otherwise diverge from the frozen-text hash). Single implementation,
        // never a second hash (red line 3). Blank SQL keeps the column NULL.
        if (StringUtils.isNotBlank(request.getQueryBody())) {
            auditDO.setSqlFingerprint(
                com.clougence.clouddm.console.web.component.governance.GovSqlHashUtils.hash(request.getQueryBody()));
        }
        auditDO.setDsId(dsId);
        auditDO.setUid(auditUid);
        auditDO.setUserName(userName);
        auditDO.setLogIp(RdpHostUtil.getHostIp());
        auditDO.setRequester(request.getRequester());
        auditDO.setDataSourceType(dsDO.getDataSourceType());
        auditDO.setDsDesc(dsDO.getInstanceId() + "(" + dsDO.getInstanceDesc() + ")");
        auditDO.setStatus(SqlStatus.PENDING);
        this.execDal.sqlAuditMapper().insert(auditDO);
    }

    @Override
    @Transactional(rollbackFor = Throwable.class, propagation = Propagation.REQUIRED)
    public void recordAudit(SqlExecNotifyDTO audit, String wsn) {
        LogInfo logInfo = recodeSql(audit, wsn);
        if (logInfo != null) {
            logger.info(logInfo.toString());
        }
    }

    private LogInfo recodeSql(SqlExecNotifyDTO dto, String wsn) {
        if (dto.getType() == Type.COMMIT) {
            execDal.sqlAuditMapper().confirmSession(dto.getSessionId());
            return LogInfo.getCommitLogInfo(dto);
        } else if (dto.getType() == Type.ROLLBACK) {
            execDal.sqlAuditMapper().rollbackSession(dto.getSessionId());
            return LogInfo.getRollbackLogInfo(dto);
        } else if (dto.getType() == Type.START_TRANSACTION) {
            return LogInfo.getStartTransaction(dto);
        } else if (dto.getType() == Type.SQL_START) {
            if (StringUtils.isBlank(dto.getQueryId())) {
                return null;
            }
            DmExecSqlAuditDO auditDO = this.startPreparedAudit(dto, wsn);
            if (auditDO != null) {
                return LogInfo.getStartLogInfo(auditDO, dto);
            }
            return null;
        }

        // C6/M7: sanitize BEFORE the 65535 truncation (a truncated pattern could otherwise
        // leave a half-matched data value behind) — the DB column and the app-log twin below
        // share this sanitized source
        String message = getString(DbErrorSanitizer.sanitize(dto.getMessage()));
        if (StringUtils.isBlank(dto.getQueryId())) {
            return null;
        }
        int updated = this.execDal.sqlAuditMapper().completeByQueryId(dto.getQueryId(), dto.getSessionId(), dto.getStatus().name(), dto.getAffectLine(), message, dto.getTime());
        if (updated == 0) {
            return null;
        }

        DmExecSqlAuditDO auditDO = this.execDal.sqlAuditMapper().queryByQueryId(dto.getQueryId());
        return LogInfo.getEndLogInfo(dto, auditDO);
    }

    private DmExecSqlAuditDO startPreparedAudit(SqlExecNotifyDTO dto, String wsn) {
        DmExecSqlAuditDO auditDO = this.execDal.sqlAuditMapper().queryByQueryId(dto.getQueryId());
        if (auditDO == null) {
            return null;
        }

        auditDO.setSessionId(dto.getSessionId());
        auditDO.setClientIp(dto.getClientIp());
        auditDO.setWorkSeqNumber(wsn);
        auditDO.setOperateTime(dto.getTime());
        auditDO.setStatus(SqlStatus.RUNNING);
        auditDO.setAffectLine(0);
        auditDO.setEndTime(null);
        auditDO.setMessage(null);
        int updated = this.execDal.sqlAuditMapper().markRunningByQueryId(auditDO);
        return updated == 0 ? null : auditDO;
    }

    private String getString(String str) {
        if (str == null) {
            return null;
        }
        if (str.length() > 65535) {
            str = str.substring(0, 65500);
            str += "...";
        }
        return str;
    }

    @Override
    public void notifyRegister(String wsn) {
        this.execDal.sqlAuditMapper().updateErrorSql(wsn);
    }

    @Override
    public void init() throws Exception {
        if (!this.inited.compareAndSet(false, true)) {
            return;
        }
        this.scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1, ThreadUtils.daemonThreadFactory(this.getClass().getClassLoader(), "Sql-audit-delete-%s"));
        this.scheduledThreadPoolExecutor.scheduleWithFixedDelay(this::deleteTimeoutLog, 1, 30, TimeUnit.MINUTES);
    }

    @Override
    public void stop() {

    }

    /** C8 M1 (design D2): op_audit retention defaults/clamps — long retention (compliance face). */
    static final int OP_AUDIT_RETENTION_DEFAULT_DAYS = 365;
    static final int OP_AUDIT_RETENTION_MIN_DAYS     = 30;
    static final int OP_AUDIT_RETENTION_MAX_DAYS     = 3650;
    /** C8 M1 (design D2): biz_log retention defaults/clamps — short retention (high-frequency process rows). */
    static final int BIZ_LOG_RETENTION_DEFAULT_DAYS  = 90;
    static final int BIZ_LOG_RETENTION_MIN_DAYS      = 7;
    static final int BIZ_LOG_RETENTION_MAX_DAYS      = 3650;

    /**
     * The 30-min retention sweep — three independent legs (sql_audit / op_audit / biz_log), each
     * fully try-caught: one table's failure never affects another and NEVER kills the
     * scheduleWithFixedDelay periodic task (C8 M1, design D2 "调度不停摆" — the D-3 lesson: an
     * unguarded exception here silently stops every future round).
     */
    private void deleteTimeoutLog() {
        Date now = new Date();
        java.util.List<DmAuthUserDO> primaryUsers;
        try {
            primaryUsers = userService.listPrimaryUser();
        } catch (RuntimeException e) {
            logger.error("[AuditRetention] primary-user listing failed — this cleanup round is skipped", e);
            return;
        }
        if (primaryUsers == null) {
            return;
        }

        for (DmAuthUserDO rdpUserDO : primaryUsers) {
            String uid = rdpUserDO.getUid();
            try {
                deleteSqlAuditBefore(uid, now);
            } catch (RuntimeException e) {
                logger.error("[AuditRetention] sql-audit cleanup failed for uid {} (the other legs proceed)", uid, e);
            }
            try {
                deleteOpAuditBefore(uid, now);
            } catch (RuntimeException e) {
                logger.error("[AuditRetention] op-audit cleanup failed for uid {} (the other legs proceed)", uid, e);
            }
        }

        try {
            deleteBizLogBefore(primaryUsers, now);
        } catch (RuntimeException e) {
            logger.error("[AuditRetention] biz-log cleanup failed (the per-user legs already ran)", e);
        }
    }

    /** The pre-existing sql_audit leg + the D-3 guard: a missing config row warns and skips the round instead of NPE-ing the scheduler dead. */
    private void deleteSqlAuditBefore(String uid, Date now) {
        DmSysUserConfDO configDO = systemDal.userConfMapper().queryByUidAndConfigName(uid, RootUserConfig.Fields.sqlAuditRetentionDays);
        if (configDO == null) {
            // D-3 (C8 M1): queryByUidAndConfigName has no row → the old code NPE-ed here and
            // scheduleWithFixedDelay swallowed the periodic task forever (silent standstill).
            logger.warn("[AuditRetention] sql-audit retention config row missing for uid {} — this round is skipped", uid);
            return;
        }
        int day = 30;
        String configValue = configDO.getConfigValue();
        if (StringUtils.isNotEmpty(configValue) && StringUtils.isNumeric(configValue)) {
            try {
                day = Integer.parseInt(configValue);
                if (day > 60) {
                    day = 60;
                } else if (day < 1) {
                    day = 1;
                }
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        }

        Date date = new Date(now.getTime() - (long) day * 24 * 60 * 60 * 1000);

        int deleteCount;

        do {
            deleteCount = execDal.sqlAuditMapper().deleteAuditBeforeDate(uid, date);
        } while (deleteCount > 0);
    }

    /**
     * C8 M1 op_audit leg (design D2): per primary account (owner_uid scope, the tenant-isolated
     * sql_audit shape), LIMIT-500 batches. Fail-safe KV read (missing/unparsable row → default
     * 365 — the B7 six-KV read discipline), clamp 30-3650, {@code <= 0} = cleanup DISABLED
     * (permanent retention — the pre-pilot-sign-off escape hatch, plan L228).
     */
    private void deleteOpAuditBefore(String uid, Date now) {
        Integer configured = systemDal.fetchUserConf(uid, RootUserConfig.Fields.opAuditRetentionDays, Integer.class, OP_AUDIT_RETENTION_DEFAULT_DAYS);
        int day = configured == null ? OP_AUDIT_RETENTION_DEFAULT_DAYS : configured;
        if (day <= 0) {
            return; // disabled — permanent retention by explicit configuration
        }
        day = Math.min(Math.max(day, OP_AUDIT_RETENTION_MIN_DAYS), OP_AUDIT_RETENTION_MAX_DAYS);
        Date date = new Date(now.getTime() - (long) day * 24 * 60 * 60 * 1000);

        long total = 0;
        int deleteCount;
        do {
            deleteCount = monitorDal.opAuditMapper().deleteBeforeDate(uid, date);
            total += deleteCount;
        } while (deleteCount > 0);
        if (total > 0) {
            // per-round summary only (the cleanup itself is high-frequency — never row-audited)
            logger.info("[AuditRetention] op_audit cleanup for uid {}: deleted {} row(s) older than {} day(s)", uid, total, day);
        }
    }

    /**
     * C8 M1 biz_log leg (design D2): dm_mon_biz_log has NO owner column (platform-global
     * process/forensics/observation rows), so one global cleanup runs with the MOST CONSERVATIVE
     * resolution across the primary accounts — any primary configuring {@code <= 0} disables the
     * leg entirely; otherwise the LARGEST effective (clamped 7-3650, default 90) day count wins,
     * i.e. a row is deleted only once every tenant's retention window has passed.
     */
    private void deleteBizLogBefore(java.util.List<DmAuthUserDO> primaryUsers, Date now) {
        Integer days = null;
        for (DmAuthUserDO rdpUserDO : primaryUsers) {
            Integer configured = systemDal.fetchUserConf(rdpUserDO.getUid(), RootUserConfig.Fields.bizLogRetentionDays, Integer.class, BIZ_LOG_RETENTION_DEFAULT_DAYS);
            int day = configured == null ? BIZ_LOG_RETENTION_DEFAULT_DAYS : configured;
            if (day <= 0) {
                return; // any primary disabling it disables the global leg (conservative)
            }
            day = Math.min(Math.max(day, BIZ_LOG_RETENTION_MIN_DAYS), BIZ_LOG_RETENTION_MAX_DAYS);
            days = days == null ? day : Math.max(days, day);
        }
        if (days == null) {
            return; // no primary account — nothing to resolve the KV from
        }
        Date date = new Date(now.getTime() - (long) days * 24 * 60 * 60 * 1000);

        long total = 0;
        int deleteCount;
        do {
            deleteCount = monitorDal.bizLogMapper().deleteBeforeDate(date);
            total += deleteCount;
        } while (deleteCount > 0);
        if (total > 0) {
            logger.info("[AuditRetention] biz_log cleanup: deleted {} row(s) older than {} day(s)", total, days);
        }
    }

    @Setter
    private static class LogInfo {

        // all
        private Type      type;

        private String    sql;
        private String    uid;
        private String    username;
        private String    clientIp;
        private String    sessionId;
        private Requester requester;
        private Long      dsId;
        private String    wsn;

        private String    message;
        private Date      time;

        private long      affectLine;

        private SqlStatus sqlStatus;

        public static LogInfo getStartLogInfo(DmExecSqlAuditDO auditDO, SqlExecNotifyDTO dto) {
            LogInfo logInfo = new LogInfo();
            logInfo.setType(Type.SQL_START);
            logInfo.setSql(auditDO.getExecSql());
            logInfo.setUid(auditDO.getUid());
            logInfo.setDsId(auditDO.getDsId());
            logInfo.setUsername(auditDO.getUserName());
            logInfo.setClientIp(auditDO.getClientIp());
            logInfo.setSessionId(auditDO.getSessionId());
            logInfo.setRequester(auditDO.getRequester());
            logInfo.setMessage(auditDO.getMessage());
            logInfo.setWsn(auditDO.getWorkSeqNumber());
            logInfo.setTime(dto.getTime());
            return logInfo;
        }

        public static LogInfo getEndLogInfo(SqlExecNotifyDTO dto, DmExecSqlAuditDO auditDO) {
            LogInfo logInfo = new LogInfo();
            logInfo.setType(Type.SQL_END);
            logInfo.setSql(auditDO.getExecSql());
            logInfo.setAffectLine(dto.getAffectLine());
            // C6/M7: the app-log exit reads the PERSISTED (sanitized) message, never the raw
            // dto text — both exits share one sanitized source (design §7)
            logInfo.setMessage(auditDO.getMessage());
            logInfo.setTime(dto.getTime());
            logInfo.setSessionId(dto.getSessionId());
            logInfo.setSqlStatus(dto.getStatus());
            logInfo.setAffectLine(dto.getAffectLine());
            return logInfo;
        }

        public static LogInfo getCommitLogInfo(SqlExecNotifyDTO dto) {
            LogInfo logInfo = new LogInfo();
            logInfo.setType(Type.COMMIT);
            logInfo.setTime(dto.getTime());
            logInfo.setSessionId(dto.getSessionId());
            return logInfo;
        }

        public static LogInfo getRollbackLogInfo(SqlExecNotifyDTO dto) {
            LogInfo logInfo = new LogInfo();
            logInfo.setType(Type.ROLLBACK);
            logInfo.setTime(dto.getTime());
            logInfo.setSessionId(dto.getSessionId());
            return logInfo;
        }

        public static LogInfo getStartTransaction(SqlExecNotifyDTO dto) {
            LogInfo logInfo = new LogInfo();
            logInfo.setType(Type.START_TRANSACTION);
            logInfo.setTime(dto.getTime());
            logInfo.setSessionId(dto.getSessionId());
            return logInfo;
        }

        @Override
        public String toString() {
            String result = "";
            String formatTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(this.time);
            if (type == Type.SQL_START) {
                result = String
                    .format("%s sessionId: %s, [START] uid: %s, username:%s, clientIp: %s, wsn: %s, requester: %s, dsId: %3s, sql: %s", formatTime, this.sessionId, this.uid, this.username, this.clientIp, this.wsn, this.requester, this.dsId, this.sql);
            } else if (type == Type.SQL_END) {
                result = String
                    .format("%s sessionId: %s, [%s] affectLine: %d, sql: %s, message: %s", formatTime, this.sessionId, this.sqlStatus, this.affectLine, this.sql, this.message);
            } else if (type == Type.COMMIT) {
                result = String.format("%s sessionId: %s, [COMMIT]", formatTime, this.sessionId);
            } else if (type == Type.ROLLBACK) {
                result = String.format("%s sessionId: %s, [ROLLBACK]", formatTime, this.sessionId);
            } else if (type == Type.START_TRANSACTION) {
                result = String.format("%s sessionId: %s, [START_TRANSACTION]", formatTime, this.sessionId);
            }

            return result;
        }

    }
}
