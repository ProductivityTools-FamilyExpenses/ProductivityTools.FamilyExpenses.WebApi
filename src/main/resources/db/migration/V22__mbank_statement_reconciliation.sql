-- Table storing imported mBank HTML statement operations for reconciliation against spendings
CREATE TABLE IF NOT EXISTS mbank_statement_operations (
    id BIGSERIAL PRIMARY KEY,
    operation_date DATE NOT NULL,
    raw_description TEXT NOT NULL,
    title TEXT,
    operation_type VARCHAR(128),
    account_name VARCHAR(128),
    account_iban VARCHAR(64),
    src_account VARCHAR(128),
    dst_account VARCHAR(128),
    mbank_category VARCHAR(128),
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    occurrence_in_day INT NOT NULL DEFAULT 1,
    row_fingerprint VARCHAR(128) NOT NULL UNIQUE,

    reconciliation_status VARCHAR(32) NOT NULL DEFAULT 'UNRECONCILED',
    match_type VARCHAR(32),
    spending_id BIGINT UNIQUE REFERENCES spendings(id) ON DELETE SET NULL,
    candidate_spending_id BIGINT REFERENCES spendings(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    reconciled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_mbank_stmt_ops_date
    ON mbank_statement_operations(operation_date);

CREATE INDEX IF NOT EXISTS idx_mbank_stmt_ops_status
    ON mbank_statement_operations(reconciliation_status);

CREATE INDEX IF NOT EXISTS idx_mbank_stmt_ops_match
    ON mbank_statement_operations(src_account, operation_date, amount);

-- Allow spendings rows to originate from an imported mBank statement without a raw email
ALTER TABLE spendings
    ALTER COLUMN raw_email_id DROP NOT NULL;

ALTER TABLE spendings
    ADD COLUMN IF NOT EXISTS statement_operation_id BIGINT UNIQUE
        REFERENCES mbank_statement_operations(id) ON DELETE SET NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_spendings_source'
    ) THEN
        ALTER TABLE spendings
            ADD CONSTRAINT chk_spendings_source
            CHECK (raw_email_id IS NOT NULL OR statement_operation_id IS NOT NULL);
    END IF;
END $$;
