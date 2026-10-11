-- Card authorizations ("Autoryzacja karty") are pre-authorisations, not bookings, and we only receive
-- them for the cards of this mailbox's owner (the other card on the joint account has none).
-- They move out of spendings into their own table; the booked "Obciazenie" stays the canonical spending.
CREATE TABLE card_authorizations (
    id BIGSERIAL PRIMARY KEY,
    raw_email_id BIGINT NOT NULL REFERENCES mbank_raw_emails(id),
    operation_id VARCHAR(255) NOT NULL UNIQUE,
    operation_date VARCHAR(64),
    operation_time VARCHAR(32),
    card VARCHAR(32) NOT NULL,
    merchant TEXT,
    amount NUMERIC(14, 2) NOT NULL,
    currency VARCHAR(16),
    amount_left VARCHAR(64),
    amount_left_currency VARCHAR(16),
    details TEXT NOT NULL,
    -- the booked debit (spendings row) that settled this authorization, filled by enrichment
    settled_by_spending_id BIGINT UNIQUE REFERENCES spendings(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_card_authorizations_raw_email_id ON card_authorizations(raw_email_id);
CREATE INDEX idx_card_authorizations_card_date ON card_authorizations(card, operation_date);

-- Move existing authorizations out of spendings (spending_details rows go away via ON DELETE CASCADE)
INSERT INTO card_authorizations (
    raw_email_id, operation_id, operation_date, operation_time, card, merchant,
    amount, currency, amount_left, amount_left_currency, details, created_at
)
SELECT raw_email_id, operation_id, operation_date, operation_time, src_account, name,
       amount, currency, amount_left, amount_left_currency, details, created_at
FROM spendings
WHERE operation_type = 'Autoryzacja karty'
ORDER BY id;

DELETE FROM spendings WHERE operation_type = 'Autoryzacja karty';
