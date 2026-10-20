-- Durable journal cleanup queue. Terminal request state and this task are
-- written in one transaction so cleanup intent survives a process crash.
CREATE TABLE IF NOT EXISTS tb_ticket_journal_gc_task (
 order_id BIGINT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 client_request_id VARCHAR(80) NOT NULL,
 state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 next_attempt_at BIGINT NOT NULL DEFAULT 0,
 attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(500) NOT NULL DEFAULT '',
 created_at BIGINT NOT NULL,
 completed_at BIGINT NULL,
 KEY idx_journal_gc_due(state,next_attempt_at,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotent backfill for databases that already contain terminal request
-- results when this migration is applied.  New writes enqueue in TicketWriter.
INSERT IGNORE INTO tb_ticket_journal_gc_task
    (order_id,user_id,client_request_id,state,next_attempt_at,created_at)
SELECT r.order_id,r.user_id,r.client_request_id,'PENDING',UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000,
       UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000
FROM tb_ticket_request_result r
LEFT JOIN tb_ticket_journal_gc g ON g.order_id=r.order_id
LEFT JOIN tb_ticket_journal_gc_task t ON t.order_id=r.order_id
WHERE g.order_id IS NULL AND t.order_id IS NULL
  AND r.result IN ('CREATED','REJECTED')
  AND r.accepted_at < UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000-60000;
