CREATE TABLE expenses (
    id BIGSERIAL PRIMARY KEY,
    raw_email_id BIGINT NOT NULL REFERENCES raw_emails(id),
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    operation_date VARCHAR(64),
    operation_time VARCHAR(32),
    operation_type VARCHAR(64) NOT NULL,
    src_account VARCHAR(128),
    dst_account VARCHAR(128),
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(16),
    name TEXT,
    amount_left VARCHAR(64),
    amount_left_currency VARCHAR(16),
    details TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_expenses_raw_email_id ON expenses(raw_email_id);
CREATE INDEX idx_expenses_operation_type ON expenses(operation_type);

CREATE TABLE expense_details (
    expense_id BIGINT PRIMARY KEY REFERENCES expenses(id) ON DELETE CASCADE,
    account VARCHAR(128),
    category VARCHAR(128),
    note TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
