-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
-- V9: one checkpoint per chain position (decision D5, 22 Oct 2026). Auditors may sign a
-- checkpoint on demand; a checkpoint is never replaced. V3 already makes audit_checkpoint
-- append-only (no UPDATE, DELETE or TRUNCATE); this adds that a second checkpoint for a
-- position already signed is refused, matching the archive, whose files are write-once.
CREATE UNIQUE INDEX ux_audit_checkpoint_position ON audit_checkpoint (chain_pos);
