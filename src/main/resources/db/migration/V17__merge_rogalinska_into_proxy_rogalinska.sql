-- Merge category "Rogalinska" into "ProxyRogalinska" (the property costs belong to the Proxy* group).
-- Idempotent: safe to re-run; also keeps the legacy sheet import consistent (future imports of "Rogalinska"
-- would re-create the category, so the mapping table is updated as well).

INSERT INTO dictionary_category (name) VALUES ('ProxyRogalinska')
ON CONFLICT (name) DO NOTHING;

-- 1. transactions
UPDATE spending_details
SET category = 'ProxyRogalinska', updated_at = NOW()
WHERE category = 'Rogalinska';

-- 2. automatic mapping rules (merchant/title -> category)
UPDATE dictionary_category_mapping
SET category = 'ProxyRogalinska'
WHERE category = 'Rogalinska';

-- 3. the now-unused category itself
DELETE FROM dictionary_category WHERE name = 'Rogalinska';
