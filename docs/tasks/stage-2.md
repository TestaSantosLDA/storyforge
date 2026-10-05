# Tasks — Stage 2 (narration audio)

Spec: [stage-2](../pipeline-a/stage-2-narration-audio.md).

## Done

- [x] Sidecar `/tts` (Kokoro-82M), `/transcribe` (faster-whisper small.en), `/voices`; loudness normalisation, silence and clipping numbers
- [x] Voice auditions page for picking the narrator (all 27 Kokoro English voices)
- [x] `VoiceEngine` and `Transcriber` interfaces with a sidecar implementation
- [x] Per-line clips in the speaker's voice; QA: transcript match, speaking rate, silence, clipping, voice
- [x] Retries at a different speed (Kokoro is deterministic); 3 per line, then needs attention listing the lines
- [x] Clips cached by input hash and reused on resume or restart
- [x] Stitching with same-speaker / speaker-change / scene pauses; timing manifest; length check with "accept"
- [x] Pronunciation dictionary (`channel/pronunciation.yaml`), applied to engine text only; add-and-retry on the page
- [x] Send back to Gate B from a flagged audio step (counts as a script rejection)
- [x] Gate B approval now starts Stage 2 automatically
- [x] Tests: every Stage 2 scenario with fake Kokoro/Whisper; transcript-match rules
- [x] Real run: the Tamsin script narrated in 3.3 min (32 lines); fix from it: allowances for very short lines

## Open

- [ ] Pick the narrator voice (placeholder `bm_george`)
- [ ] Install FFmpeg before Stage 3 (`winget install Gyan.FFmpeg`)
- [ ] Push once PR #6 is merged
