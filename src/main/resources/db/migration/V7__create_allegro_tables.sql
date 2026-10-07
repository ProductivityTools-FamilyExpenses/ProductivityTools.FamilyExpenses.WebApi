CREATE TABLE allegro_raw_emails (
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

CREATE INDEX idx_allegro_raw_emails_status ON allegro_raw_emails(status);

CREATE TABLE allegro_purchases (
    id BIGSERIAL PRIMARY KEY,
    allegro_raw_email_id BIGINT NOT NULL REFERENCES allegro_raw_emails(id),
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    purchase_date DATE NOT NULL,
    full_price NUMERIC(14, 2) NOT NULL,
    item_name TEXT NOT NULL,
    item_cost NUMERIC(14, 2) NOT NULL,
    multiple_items VARCHAR(128),
    item_count VARCHAR(64),
    item_price VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_allegro_purchases_full_price ON allegro_purchases(full_price);
