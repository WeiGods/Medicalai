CREATE TABLE IF NOT EXISTS visit_transcript (
    visit_id uuid PRIMARY KEY REFERENCES visit(id),
    snapshot_id uuid NOT NULL REFERENCES dialogue_snapshot(id),
    transcript_text text NOT NULL DEFAULT '',
    edited boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_recording_status ON recording(visit_id,status);
CREATE INDEX IF NOT EXISTS ix_record_version_record ON medical_record_version(record_id,version_no DESC);
CREATE INDEX IF NOT EXISTS ix_record_export_record ON record_export(record_id,created_at DESC);
