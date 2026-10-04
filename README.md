# Kids' / Family Animated Story Channel

Local pipeline that turns story concepts into narrated, animated family stories for YouTube, plus vertical clips for Shorts, TikTok and Instagram Reels.

## Run locally

```bash
docker compose up -d
cd backend && ./mvnw spring-boot:run
```

Tests need Docker running (they start their own Postgres): `cd backend && ./mvnw test`.

## Docs

- [Overview](docs/00-overview.md): concept, audience, style, architecture, hosting
- [Foundations](docs/foundations.md): stack, status classification, restart sweep, GPU lease, cost budget
- **Pipeline A — long-form YouTube** ([summary](docs/pipeline-a/README.md))
  - [Stage 0 — Topics, story queue & management page](docs/pipeline-a/stage-0-topics-queue-management-page.md)
  - [Stage 1 — Story & script generation](docs/pipeline-a/stage-1-story-and-script.md)
  - [Stage 2 — Narration / audio](docs/pipeline-a/stage-2-narration-audio.md)
  - [Stage 3 — Visual generation](docs/pipeline-a/stage-3-visual-generation.md)
  - [Stage 4 — Assembly & final review](docs/pipeline-a/stage-4-assembly-final-review.md)
  - [Stage 5 — Publish](docs/pipeline-a/stage-5-publish.md)
- **Pipeline B — vertical clips** ([summary](docs/pipeline-b/README.md))
  - [B0 — Clip queue](docs/pipeline-b/b0-clip-queue.md)
  - [B1 — Clip selection](docs/pipeline-b/b1-clip-selection.md)
  - [B2 — Vertical re-assembly](docs/pipeline-b/b2-vertical-reassembly.md)
  - [B3 — Per-platform formatting](docs/pipeline-b/b3-per-platform-formatting.md)
  - [B4 — Publish](docs/pipeline-b/b4-publish.md)

Working with Claude Code: see [CLAUDE.md](CLAUDE.md) for the architecture rules and build order.
