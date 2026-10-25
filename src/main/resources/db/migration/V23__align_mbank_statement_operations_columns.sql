-- Align mbank_statement_operations with the columns used by MBankStatementDiffService
ALTER TABLE mbank_statement_operations RENAME COLUMN occurrence_in_day TO same_day_sequence;
ALTER TABLE mbank_statement_operations RENAME COLUMN src_account TO src_account_short;
ALTER TABLE mbank_statement_operations RENAME COLUMN dst_account TO dst_account_short;
ALTER TABLE mbank_statement_operations RENAME COLUMN account_iban TO src_account_full;
ALTER TABLE mbank_statement_operations ALTER COLUMN src_account_full TYPE VARCHAR(128);

ALTER TABLE mbank_statement_operations
    ADD COLUMN IF NOT EXISTS dst_account_full VARCHAR(128),
    ADD COLUMN IF NOT EXISTS statement_period_from DATE,
    ADD COLUMN IF NOT EXISTS statement_period_to DATE,
    ADD COLUMN IF NOT EXISTS date_diff_days INT,
    ADD COLUMN IF NOT EXISTS match_score INT;

CREATE INDEX IF NOT EXISTS idx_mbank_stmt_ops_candidate
    ON mbank_statement_operations(candidate_spending_id);
