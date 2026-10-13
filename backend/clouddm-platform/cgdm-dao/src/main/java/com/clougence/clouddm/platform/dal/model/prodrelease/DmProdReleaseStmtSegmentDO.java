/*
 * Copyright 2026 杭州开云集致科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.clougence.clouddm.platform.dal.model.prodrelease;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

/**
 * ADR-013 M1: frozen segment-plan manifest row for a production release statement —
 * the release-side frozen copy of the source group's {@code precheck_result.segments}
 * plan (one row per segment, uk_rstmt_seg (release_stmt_id, seg_idx)).
 * <p>
 * Lifecycle mirrors {@link DmProdReleaseStmtSchemaDO}: propagated inside the
 * createRelease/resubmit merge transaction, cascade-deleted on returnStmt
 * (deleteByReleaseStmtId) and on returnRelease/handleRejected/handleCancelled
 * (deleteByReleaseId). NO stock backfill — rows exist only for releases merged
 * after V202610120001, and only when the source plan carries ≥1 routed
 * (GHOST ∨ DML_BATCH) segment; a pure-normal group keeps the null-plan legacy shape.
 * <p>
 * Column groups:
 * <ul>
 * <li>PLAN (frozen at merge, never updated): segIdx..idempotent. Bounds are 1-based in
 *     the GLOBAL exec_order domain WITH HOLES (governance-contracts stmt_index alignment;
 *     per-segment re-densing forbidden). rowsEstimated carries the GHOST probe estimate
 *     (TicketSegment.rows) or the DML_BATCH routing signal (TicketSegment.estimatedRows) —
 *     only one is ever set per segment by construction. sqlText = FULL frozen statement
 *     text (GHOST/DML_BATCH).</li>
 * <li>RUNTIME (M2 consumers only; M1 leaves these at defaults): status..finishedAt,
 *     mutated only inside the release-row-lock window (single-writer discipline).
 *     checkpoint is reserved for the future DML_BATCH release-side executor
 *     (O-C7-1) — never consumed in this milestone.</li>
 * </ul>
 * M1 stage: WRITE-ONLY — no dispatch path reads this table yet (execution behavior
 * byte-identical); the stmt-level integrity pin lives in dm_prod_release_stmt.plan_hash
 * (ReleasePlanHashUtils canonical hash over the plan columns).
 */
@Getter
@Setter
@TableName(value = "dm_prod_release_stmt_segment")
public class DmProdReleaseStmtSegmentDO {

    @TableId(type = IdType.AUTO)
    private Long    id;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date    gmtCreate;

    @TableField(insertStrategy = FieldStrategy.NOT_NULL, updateStrategy = FieldStrategy.NOT_NULL)
    private Date    gmtModified;

    /** Redundant release id — cascade-delete key (deleteByReleaseId), mirrors the schema table. */
    private Long    releaseId;

    /** FK → dm_prod_release_stmt.id (1 stmt : N segments). */
    private Long    releaseStmtId;

    // ---- plan columns (frozen at merge — plan_hash input) ----

    /** 1-based dense segment position within the stmt plan (mirrors TicketSegment.segIdx). */
    private int     segIdx;

    /** AUTOEXEC | GHOST | DML_BATCH (TicketSegment.TYPE_* wire values). */
    private String  type;

    /** AUTOEXEC only: inclusive [from,to] bounds in the 1-based exec_order domain. */
    private Integer stmtRangeFrom;

    private Integer stmtRangeTo;

    /** GHOST / DML_BATCH only: the single statement's 1-based exec_order position. */
    private Integer stmtIndex;

    private String  tableName;

    /** GHOST probe estimate ∨ DML_BATCH routing signal (mutually exclusive by construction). */
    private Long    rowsEstimated;

    /** GHOST / DML_BATCH: the FULL frozen statement text. */
    private String  sqlText;

    /** DML_BATCH only: frozen batch key (column is the validated bare identifier). */
    private String  batchKeyColumn;

    /** DML_BATCH only: INT | DECIMAL0 | DATETIME | STRING (DmlBatchRangeSpec wire values). */
    private String  batchKeyType;

    /** DML_BATCH only: self-referencing SET / non-deterministic function detection result. */
    private Boolean idempotent;

    // ---- runtime columns (M2 consumers; defaults until then) ----

    /** PENDING | EXECUTING | SUCCESS | FAILED (TicketSegment.STATUS_* wire values). */
    private String  status;

    /** GHOST / DML_BATCH: the AsyncTask row id (recorded at launch by the M2 dispatch). */
    private Long    taskId;

    /** GHOST: tool session id (release-side session economy, M2). */
    private String  sessionId;

    /** AUTOEXEC: the segment job's biz id (M2). */
    private String  jobBizId;

    /** Terminal-evidence freeze (M2, mirrors the ticket-side summary contract). */
    private String  summary;

    private String  error;

    /** Reserved for the release-side DML_BATCH executor (O-C7-1) — never written in M1/M2. */
    private String  checkpoint;

    private Long    startedAt;

    private Long    finishedAt;
}
