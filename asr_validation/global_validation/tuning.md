# Speaker turn tuning record

Update date: 2026-09-17

## Decision

- Current offline candidate: `global_w800_island500`.
- Do not use `800 ms` island merge as the default; it reduced the audio 1
  ratio but increased audio 2 errors and removed a real `B>A` short turn.
- Keep `500 ms` and `600 ms` equivalent results in mind; `500 ms` is the
  current preferred default.
- This is still an offline validator setting. Do not claim it is already the
  realtime service default until the realtime provisional/backfill work lands.

## Offline candidate parameters

| Parameter | Value |
|---|---:|
| VAD end silence | 600 ms |
| Speaker window | 800 ms |
| Speaker hop | 200 ms |
| Confirm windows | 3 |
| Speaker confirm windows | 5 |
| Speaker min span | 2000 ms |
| Minimum turn | 400 ms |
| Max speakers | 2 |
| Offline global reclassify | enabled |
| Offline global iterations | 25 |
| Offline global match threshold | 0.32 |
| Offline global margin threshold | 0.04 |
| Offline global unknown threshold | 0.25 |
| Offline global gap fill | 1000 ms |
| Offline global minimum run windows | 2 |
| Offline global minimum speaker island | 500 ms |

## Reproduce command

```powershell
python asr_validation/validate_speaker_turns.py `
  --audio <AUDIO> `
  --output <RESULT_JSON> `
  --vad-end-silence-ms 600 `
  --window-ms 800 `
  --hop-ms 200 `
  --confirm-windows 3 `
  --speaker-confirm-windows 5 `
  --speaker-min-span-ms 2000 `
  --min-turn-ms 400 `
  --max-speakers 2 `
  --offline-global-reclassify `
  --offline-global-iterations 25 `
  --offline-global-match-threshold 0.32 `
  --offline-global-margin-threshold 0.04 `
  --offline-global-unknown-threshold 0.25 `
  --offline-global-gap-fill-ms 1000 `
  --offline-global-min-run-windows 2 `
  --offline-global-min-speaker-island-ms 500 `
  --embedding-cache <EMBEDDING_PT>
```

For cached reruns, add `--vad-segments-json <BASELINE_JSON>` and keep the same
embedding cache.

## Human annotation outcome

- 23/23 annotation rows are valid; 14 rows intentionally omit millisecond
  boundary truth, so boundary timing metrics are not generated.
- `500/600 ms`: 4 confirmed short-turn errors across two audios.
- `800 ms`: 5 confirmed short-turn errors across two audios.
- Boundary sequence: exact 4/7, contiguous subsequence 7/7, hard order errors
  0. The three exact mismatches only add context speakers before/after the
  human sequence inside the 2-second clip.
- Combined `<500 ms` turn ratio for `500 ms` is 1.67%; still above the 1%
  target.

## Realtime boundary

The realtime pipeline is not allowed to use future audio to rewrite early turns.
Expected behavior for the next implementation is:

- Use a rolling buffer of about 1.2-1.6 s.
- Emit provisional speakers while confirmation is insufficient.
- Back-split buffered text after a second speaker is confirmed.
- Keep separate contexts per speaker.
- Target finalization latency of 1.2-1.6 s after audio input.

## Image build note

The service image currently bakes the realtime changes in
`serve_realtime_ws.py`. The offline validator files are not runtime service
dependencies and do not need to be copied into the service image. Rebuild with
the existing `ai_service/Dockerfile`; add a new tag rather than overwriting a
known-good image until smoke tests pass.
