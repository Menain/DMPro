// C10 recovery-type grading (task 10-14-recovery-type-grading) display maps — the design D6
// 措辞表 single authority for the ticket-detail recovery Tag, the constant 未演练 companion and
// the per-statement inference fold. Colors follow the D6 色阶: IRREVERSIBLE red / the two
// NON-ROLLBACK routed semantics (BATCH_CHECKPOINT, GHOST_OLD_TABLE) orange / the two material
// semantics (COMPENSATION_SQL, BACKUP_TEMPLATE) blue / TRANSACTION green / UNSPECIFIED grey.
//
// Copy discipline (red lines 2/3):
// - BATCH_CHECKPOINT wording NEVER says 回滚 — the caliber is 部分成功非回滚 (committed batches
//   are kept; the 分批三点明示 copy owns the user-facing wording);
// - GHOST_OLD_TABLE wording is 删除前可人工回退 — never "rollback-capable at any time";
// - every surface pairs the grade with the constant 未演练 annotation (recoveryVerified is a
//   read-time CONSTANT false — the platform has NO recovery verification/drill mechanism,
//   O-C10-1); nothing here may ever read as an automatic-rollback promise (计划 §2 L39 纪律:
//   the literal forbidden phrase is deliberately NOT quoted here so the M2.5 grep stays clean).
//
// The backend StmtView.note is a zh debug string and is deliberately NOT on the wire — the
// semantic note is re-rendered here via i18n off inferredRecovery (M2.2 措辞表覆盖).

export const RECOVERY_TYPE_TAG_COLOR = {
  IRREVERSIBLE: 'error',
  BATCH_CHECKPOINT: 'warning',
  GHOST_OLD_TABLE: 'warning',
  COMPENSATION_SQL: 'primary',
  BACKUP_TEMPLATE: 'primary',
  TRANSACTION: 'success',
  UNSPECIFIED: 'default'
};

export const RECOVERY_TYPE_LABEL_I18N = {
  IRREVERSIBLE: 'recovery-type-irreversible',
  BATCH_CHECKPOINT: 'recovery-type-batch-checkpoint',
  GHOST_OLD_TABLE: 'recovery-type-ghost-old-table',
  COMPENSATION_SQL: 'recovery-type-compensation-sql',
  BACKUP_TEMPLATE: 'recovery-type-backup-template',
  TRANSACTION: 'recovery-type-transaction',
  UNSPECIFIED: 'recovery-type-unspecified'
};

export const RECOVERY_TYPE_NOTE_I18N = {
  IRREVERSIBLE: 'recovery-type-irreversible-note',
  BATCH_CHECKPOINT: 'recovery-type-batch-checkpoint-note',
  GHOST_OLD_TABLE: 'recovery-type-ghost-old-table-note',
  COMPENSATION_SQL: 'recovery-type-compensation-sql-note',
  BACKUP_TEMPLATE: 'recovery-type-backup-template-note',
  TRANSACTION: 'recovery-type-transaction-note',
  UNSPECIFIED: 'recovery-type-unspecified-note'
};

export function recoveryTagColor(recoveryType) {
  return RECOVERY_TYPE_TAG_COLOR[recoveryType] || 'default';
}

export function recoveryLabelI18n(recoveryType) {
  return RECOVERY_TYPE_LABEL_I18N[recoveryType] || RECOVERY_TYPE_LABEL_I18N.UNSPECIFIED;
}

export function recoveryNoteI18n(recoveryType) {
  return RECOVERY_TYPE_NOTE_I18N[recoveryType] || RECOVERY_TYPE_NOTE_I18N.UNSPECIFIED;
}
