ALTER TABLE spending_details
    ADD COLUMN allegro_raw_email_id BIGINT UNIQUE REFERENCES allegro_raw_emails(id);

CREATE INDEX idx_spending_details_allegro_raw_email_id ON spending_details(allegro_raw_email_id);
