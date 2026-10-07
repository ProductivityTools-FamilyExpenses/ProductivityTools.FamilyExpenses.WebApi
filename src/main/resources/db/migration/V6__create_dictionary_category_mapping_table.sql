CREATE TABLE dictionary_category_mapping (
    id BIGSERIAL PRIMARY KEY,
    key TEXT NOT NULL UNIQUE,
    category VARCHAR(128) NOT NULL REFERENCES dictionary_category(name) ON UPDATE CASCADE
);

ALTER TABLE spending_details
    ADD CONSTRAINT fk_spending_details_category
    FOREIGN KEY (category) REFERENCES dictionary_category(name) ON UPDATE CASCADE;
