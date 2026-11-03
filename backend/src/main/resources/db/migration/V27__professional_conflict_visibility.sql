-- SNIGF/NILGS - Copyright (c) 2026 FLYT.
-- Author: Pythagore.
--
-- V27: a professional sees their own deposit's conflict (Slice 3, ADR-041 step 10, decision D17). DEMO only.
--
--   professional_deposits()   replaced. The professional's own deposits now read their real status (HOLDING,
--                             IN_CONFLICT, REGISTERED, WITHDRAWN, REFUSED), with:
--                               conflict         NONE; UNDER_REVIEW while a conflict naming the deposit, on
--                                                either side, is open; RESOLVED once every one is decided
--                               review_due_at    the conservators' review date, while the deposit is open
--                               action_required  NONE: no workflow asks a professional anything yet
--                             Never in it: the competing deposit, its parties, documents or number, the conflict's
--                             id, and the conservators' resolution reason (which closed_reason no longer shows
--                             when a conflict decision closed the deposit).
--
-- The lookup (§6) is unchanged: it still never says whether a title is held. Columns are listed one by one, so a
-- column added to the register later never reaches a professional without a migration saying so.

SET search_path = snigf, public;

DROP FUNCTION professional_deposits(uuid, int, int);

-- Newest first. Only the professional's own deposits, while they hold the permission.
CREATE FUNCTION professional_deposits(p_id uuid, p_limit int, p_offset int)
    RETURNS TABLE (id uuid, title_id uuid, title_number text, office_code text, kind text, status text, conflict text,
                   review_due_at timestamptz, action_required text, depositor_name text, capacity text, deposited_at timestamptz,
                   closed_at timestamptz, closed_reason text, documents bigint)
LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = pg_catalog, snigf, public, pg_temp AS $$
    SELECT t.id, t.title_id, lt.number, o.code, t.kind, t.status,
           CASE WHEN bool_or(c.status = 'OPEN') THEN 'UNDER_REVIEW'
                WHEN count(c.id) > 0 THEN 'RESOLVED'
                ELSE 'NONE' END,
           CASE WHEN t.status IN ('HOLDING', 'IN_CONFLICT') THEN t.review_due_at END,
           'NONE',
           dp.full_name, t.submitted_capacity, t.deposited_at, t.closed_at,
           -- A conflict decision writes the conservators' reason, which may name the other deposit: not shown.
           CASE WHEN bool_or(c.status = 'RESOLVED' AND c.resolved_by = t.closed_by AND c.resolution_reason = t.closed_reason) THEN NULL
                ELSE t.closed_reason END,
           (SELECT count(*) FROM deposit_document d WHERE d.transaction_id = t.id)
      FROM pending_transaction t
      JOIN land_title lt ON lt.id = t.title_id
      JOIN office o ON o.id = t.office_id
      JOIN register_party dp ON dp.id = t.depositor_party_id
      LEFT JOIN transaction_conflict c ON t.id IN (c.holding_id, c.arriving_id)
     WHERE t.submitted_by_account = current_actor()
       AND holds_professional_at(current_actor(), 'register.deposit.read', NULL)
       AND (p_id IS NULL OR t.id = p_id)
     GROUP BY t.id, lt.number, o.code, dp.full_name
     ORDER BY t.deposited_at DESC, t.id DESC
     LIMIT p_limit OFFSET p_offset
$$;

REVOKE EXECUTE ON FUNCTION professional_deposits(uuid, int, int) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION professional_deposits(uuid, int, int) TO snigf_app;
