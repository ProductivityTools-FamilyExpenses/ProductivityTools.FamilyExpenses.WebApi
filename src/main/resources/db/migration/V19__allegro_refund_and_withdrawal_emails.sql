-- Refund ("Zwrot ...") and contract-withdrawal ("Potwierdzenie odstąpienia od umowy ...") e-mails are only
-- imported for now - stored raw in their own tables, not parsed. The parsed-refunds table from V18 is dropped.
DROP TABLE IF EXISTS allegro_refunds;

CREATE TABLE allegro_refund_emails (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL UNIQUE,
    thread_id VARCHAR(128) NOT NULL,
    subject TEXT,
    email_date TIMESTAMPTZ,
    raw_html TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

CREATE TABLE allegro_withdrawal_emails (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL UNIQUE,
    thread_id VARCHAR(128) NOT NULL,
    subject TEXT,
    email_date TIMESTAMPTZ,
    raw_html TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);
