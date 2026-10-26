-- Rules "account number -> category": every spending sent from or to the account gets the category.
-- Accounts are matched by the last 6 digits (e-mails mask the counterparty account, e.g. "7410...609509").
CREATE TABLE IF NOT EXISTS account_category_rules (
    id BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(64) NOT NULL UNIQUE,
    label VARCHAR(128),
    category VARCHAR(128) NOT NULL REFERENCES dictionary_category(name) ON UPDATE CASCADE,
    -- TRUE: the rule overrides any existing category; FALSE: only fills spendings without a category
    overwrite BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO dictionary_category (name) VALUES ('PrzelewWlasny')
ON CONFLICT (name) DO NOTHING;

INSERT INTO account_category_rules (account_number, label, category, overwrite) VALUES
    ('85246191', 'Dedicated-Wyplaty', 'PrzelewWlasny', TRUE),
    ('85328922', 'ProxyDefault', 'PrzelewWlasny', TRUE)
ON CONFLICT (account_number) DO NOTHING;
