-- Optional colour of a category (CSS hex, e.g. "#1976d2") used by all report charts; NULL = automatic palette colour
ALTER TABLE dictionary_category ADD COLUMN IF NOT EXISTS color VARCHAR(16);
