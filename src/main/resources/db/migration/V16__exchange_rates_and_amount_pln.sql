-- Normalise spendings to PLN using NBP table A mid rates (rate of the last business day before the operation).
CREATE TABLE exchange_rates (
    currency CHAR(3) NOT NULL,
    rate_date DATE NOT NULL,
    rate NUMERIC(12, 6) NOT NULL,
    nbp_table_no VARCHAR(32),
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (currency, rate_date)
);

ALTER TABLE spendings
    ADD COLUMN amount_pln NUMERIC(14, 2),
    ADD COLUMN fx_rate NUMERIC(12, 6),
    ADD COLUMN fx_rate_date DATE;

-- PLN rows need no conversion
UPDATE spendings
SET amount_pln = amount, fx_rate = 1
WHERE currency = 'PLN' OR currency IS NULL OR currency = '';

CREATE INDEX idx_spendings_amount_pln_missing ON spendings(id) WHERE amount_pln IS NULL;
