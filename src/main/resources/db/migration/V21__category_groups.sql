-- Category groups: a user-defined grouping of dictionary categories (e.g. "Dom", "Dzieci").
-- One category belongs to at most one group; deleting a group leaves its categories ungrouped.
-- No groups are created here – the user defines them in the UI.
CREATE TABLE dictionary_category_group (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL UNIQUE,
    sort_order INT NOT NULL DEFAULT 0,
    color VARCHAR(16)
);

ALTER TABLE dictionary_category
    ADD COLUMN group_id BIGINT REFERENCES dictionary_category_group(id) ON DELETE SET NULL;

CREATE INDEX idx_dictionary_category_group_id ON dictionary_category(group_id);
