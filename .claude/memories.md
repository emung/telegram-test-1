# Project Memories

## Configuration model
All runtime config is environment variables, read in `Config.fromEnv()` (`src/main/java/eeu/test/Config.java`):
`BOT_TOKEN`, `BOT_USERNAME`, `ALLOWED_USER_ID`, `API_BASE_URL`. There are deliberately **no
defaults and no fallbacks** — a missing var exits with status 1 naming the variable. These
were hard-coded in `TelegramBot.java` before the Docker work; do not reintroduce literals.
Trailing slashes on `API_BASE_URL` are stripped because `ExpenseWebClient` appends `/api/v1/...`.

## Lombok requires an explicit processor path
`pom.xml` declares Lombok under `maven-compiler-plugin/<annotationProcessorPaths>`. JDK 23+
no longer runs annotation processors implicitly, so without this `mvn package` fails with
`cannot find symbol: method getDescription()` on every Lombok-generated accessor. IntelliJ
hides this because its own Lombok plugin handles generation — the failure only shows up in
Maven and Docker builds.

## Deployment topology (Raspberry Pi)
- The **bot runs in Docker**; the **expense tracker API runs natively on the Pi**, outside Docker.
- `docker-compose.yml` uses `extra_hosts: host.docker.internal:host-gateway` so the container
  reaches the host API. `API_BASE_URL` therefore points at `http://host.docker.internal:3100`.
- The API must bind `0.0.0.0`, not `127.0.0.1`, or the container cannot reach it.
- The image is **built on the Pi** (`docker compose up -d --build`) — no registry, no
  cross-compilation. Decided over buildx+registry and `docker save`/SSH.
- `linux/arm64` only: `eclipse-temurin` publishes no 32-bit ARM images for Java 26, so a
  32-bit Raspberry Pi OS will not work.

## Build output
`maven-shade-plugin` produces a self-contained `target/telegram-test-1.jar` with
`Main-Class: eeu.test.Main`. `<finalName>` is pinned to the artifact ID so the Dockerfile's
`COPY --from=build` path stays stable across version bumps.

## Secrets and PII
`.env` is gitignored; `.env.example` is the committed template and must only ever contain
placeholders (`ALLOWED_USER_ID=000000000`, not a real ID).

Three things were literal in the original `TelegramBot.java`: the bot token, the owner's
real Telegram user ID, and a LAN IP for the API. All were removed before the first commit,
so none are in git history. Note that the git *index* held the pre-edit file for a while
after the working tree was cleaned — when scrubbing secrets here, check `git grep --cached`,
not just the working tree, and re-stage.

## Expense API verbs
Update is **`PATCH /api/v1/expenses/{id}`**, not PUT — the NestJS controller declares
`@Patch(':id')` and there is no PUT route, so a PUT returns 404 `Cannot PUT /api/v1/expenses/{id}`.
`ExpenseWebClient.updateExpenseById` uses `.method("PATCH", ...)` because `HttpRequest.Builder`
has no `.PATCH()` shortcut. Everything else is POST/GET/DELETE as listed in README.md.

## Telegram parse mode is HTML, not Markdown
Replies use `parseMode("HTML")` throughout `TelegramBot.java`. Legacy `"Markdown"` was
dropped because it silently ate output: `[ID: 106]` parsed as link syntax so the brackets
never rendered, and `**bold**` is not legacy Markdown (single `*` is), so `**Totals:**`
came out as plain text. Any user-typed `_ * [` in a description could also break the parse
entirely, and the `catch` in `sendText` then resends with `parseMode(null)`, so formatting
just vanished with no visible error.

Consequence: **every interpolated value must go through `escape()`** (`& < >`) — API error
strings and `e.getMessage()` included. Literal angle brackets in usage hints are written as
`&lt;id&gt;`.

`sendText` splits at blank lines via `splitForTelegram` because Telegram caps a message at
4096 chars; `/list` exceeds that at roughly 45 expenses. Blank-line cuts keep each two-line
expense block — and its `<b>`/`<code>` tags — intact, so chunks stay parseable.
