# Tasks — Python sidecar (build step 4)

Spec: [sidecar/README.md](../../sidecar/README.md), Stage 1 "Character reference images", Stage 3 "Image generation".

## Done

- [x] FastAPI sidecar in `sidecar/`, runs natively; device auto-detect (CUDA → MPS → CPU), overridable
- [x] `POST /image`: Flux 2 Klein 4B NF4 with up to 4 references shrunk to 512 px; provenance headers
- [x] `GET /health`: device, GPU memory, model version and load state
- [x] Out-of-memory (507) and missing weights (503) reported distinctly; sidecar API tests with a stub engine
- [x] Java `ImageEngine` interface with `SidecarImageEngine`; GPU lease held while drawing
- [x] Stage 1 reference sheets: front first, side and expressions from it; Claude opens the images to check them
- [x] Sheets stored per draft; copied to `characters/<id>/` (keep-forever) on Gate A approval
- [x] Sidecar outage flags the story without a strike; Resume redoes only the sheets
- [x] Real run on the RTX 3060 Ti: ~10 s per image, 4.4 GB peak
- [x] Fixes from the real runs: retries redraw with a new seed instead of naming the flaw; three-quarter front view (no double tails); 3 draws per character, then the best goes to Gate A flagged; a restored story starts fresh

## Open

- [ ] Kokoro TTS and Whisper transcription endpoints (with Stage 2)
- [ ] Gate A "redo this character's look" with notes, without redoing the story
- [ ] Spring starting / health-checking the sidecar automatically (today: start it yourself, see README)
