-- spending_details.account held a copy of dictionary_account.name; replace it with a foreign key so that
-- renaming/removing a dictionary entry is reflected everywhere and nothing is stored twice.
ALTER TABLE spending_details
    ADD COLUMN account_id BIGINT REFERENCES dictionary_account(id) ON DELETE SET NULL;

CREATE INDEX idx_spending_details_account_id ON spending_details(account_id);

-- Backfill from the spending's own account numbers (same matching rule as the enrichment step),
-- not from the copied name, so the result is exact even if names repeat in the dictionary.
UPDATE spending_details sd
SET account_id = matched.account_id
FROM (
    SELECT s.id AS spending_id,
           COALESCE(da_src.id, da_dst.id) AS account_id
    FROM spendings s
    LEFT JOIN dictionary_account da_src
           ON s.src_account IS NOT NULL AND s.src_account <> ''
          AND (da_src.key = s.src_account OR LTRIM(da_src.key, '0') = LTRIM(s.src_account, '0'))
    LEFT JOIN dictionary_account da_dst
           ON s.dst_account IS NOT NULL AND s.dst_account <> ''
          AND (da_dst.key = s.dst_account OR LTRIM(da_dst.key, '0') = LTRIM(s.dst_account, '0'))
    WHERE COALESCE(da_src.id, da_dst.id) IS NOT NULL
) matched
WHERE sd.spending_id = matched.spending_id;

ALTER TABLE spending_details DROP COLUMN account;
