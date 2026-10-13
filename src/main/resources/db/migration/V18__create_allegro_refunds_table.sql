CREATE TABLE allegro_refunds (
    id BIGSERIAL PRIMARY KEY,
    allegro_raw_email_id BIGINT NOT NULL REFERENCES allegro_raw_emails(id),
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    refund_date DATE NOT NULL,
    amount NUMERIC(14, 2) NOT NULL,
    -- full price of the original order when the e-mail states it ("64,58 zł (z 104,57 zł)"); null otherwise
    original_amount NUMERIC(14, 2),
    partial BOOLEAN NOT NULL DEFAULT FALSE,
    seller TEXT,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_allegro_refunds_amount ON allegro_refunds(amount);
CREATE INDEX idx_allegro_refunds_original_amount ON allegro_refunds(original_amount);
