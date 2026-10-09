CREATE TABLE account_balances (
    id BIGSERIAL PRIMARY KEY,
    raw_email_id BIGINT NOT NULL REFERENCES raw_emails(id),
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    account VARCHAR(128) NOT NULL,
    balance_date DATE NOT NULL,
    operation_time VARCHAR(32),
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    details TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_account_balances_account_date ON account_balances(account, balance_date DESC);
