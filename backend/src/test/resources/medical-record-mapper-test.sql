CREATE TABLE medical_record (
    id UUID PRIMARY KEY,
    visit_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL
);
