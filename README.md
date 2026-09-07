# telegram-test-1

A Telegram bot that turns chat messages into expense records. It parses a simple
five-line message into an expense, then creates, reads, updates, searches and deletes
those expenses through a REST API.

The bot is single-user: every update is checked against one configured Telegram user ID
and everything else is rejected.

## Requirements

To run with Docker (recommended, see [Deployment](#deployment-raspberry-pi)):

- Docker Engine 20.10+ with Compose v2 (`host-gateway` support)
- A `linux/arm64` host for a Raspberry Pi 4/5 on 64-bit Raspberry Pi OS

To build and run locally:

- JDK 26 (`maven.compiler.source`/`target` are set to 26)
- Maven 3.9+

Either way you need:

- A bot token from [@BotFather](https://t.me/BotFather)
- A running expense REST API (see [Backend API](#backend-api))

## Configuration

All configuration comes from environment variables. There are no defaults — the bot
exits with status 1 and a message naming the missing variable if any are absent.

| Variable | Description |
| --- | --- |
| `BOT_TOKEN` | Bot token from @BotFather |
| `BOT_USERNAME` | The bot's username, e.g. `ExpenseParser` |
| `ALLOWED_USER_ID` | The only Telegram user ID allowed to use the bot (from [@userinfobot](https://t.me/userinfobot)) |
| `API_BASE_URL` | Base URL of the expense API, e.g. `http://host.docker.internal:3100`. Trailing slashes are stripped. |

Copy [`.env.example`](.env.example) to `.env` and fill it in. `.env` is gitignored; never
commit real values.

```bash
cp .env.example .env
```

## Deployment (Raspberry Pi)

The bot runs in Docker; the expense tracker API runs on the Pi itself, outside Docker.
`docker-compose.yml` maps `host.docker.internal` to the host gateway so the container can
reach it.

The image is built on the Pi — no cross-compilation and no registry needed. The first
build downloads the Maven dependencies and takes a few minutes; later builds reuse a
BuildKit cache mount for `~/.m2` and are much faster.

```bash
git clone <this-repo> && cd telegram-test-1
cp .env.example .env   # then edit .env with your real values
docker compose up -d --build
```

Check it came up:

```bash
docker compose logs -f bot
```

A healthy start logs `Bot @<username> started, using API at <url>`. The container is set
to `restart: unless-stopped`, so it comes back after a reboot or a crash.

To update after pulling new commits:

```bash
docker compose up -d --build
```

### Notes and gotchas

- **The API must listen on `0.0.0.0`, not `127.0.0.1`.** If it binds only to loopback, the
  container cannot reach it through `host.docker.internal` and every command will fail
  with a connection error. Check with `ss -tlnp | grep 3100` on the Pi.
- **Memory.** The container is capped at 512 MB (`mem_limit`) and the JVM is told to stay
  within 75% of that. The *build* is the heavier step — on a 2 GB Pi, add swap or build
  the image elsewhere if Maven gets OOM-killed.
- **Logs** are capped at 3 × 10 MB so they don't fill the SD card.
- **Architecture.** The image was verified on `linux/arm64`, which covers Pi 4 and Pi 5
  running 64-bit Raspberry Pi OS. A 32-bit OS (`armv7`) will not work — `eclipse-temurin`
  publishes no 32-bit ARM images for Java 26.

## Build & run without Docker

```bash
mvn clean package
```

This produces a self-contained jar at `target/telegram-test-1.jar` (Maven Shade).

```bash
BOT_TOKEN=... BOT_USERNAME=... ALLOWED_USER_ID=... API_BASE_URL=http://localhost:3100 \
  java -jar target/telegram-test-1.jar
```

The bot uses long polling, so no public URL or webhook is needed — it just needs outbound
network access to Telegram and to the expense API.

## Usage

### Adding an expense

Any message that is not a command is parsed as an expense. It must be exactly five lines:

```
1000 RON
Groceries at the corner shop
Food
Lidl
2026-09-05
```

| Line | Field | Notes |
| --- | --- | --- |
| 1 | Amount and currency | Amount must be a whole number; currency is upper-cased |
| 2 | Description | Required |
| 3 | Category | Required |
| 4 | Recipient | Required |
| 5 | Date | Required, passed through to the API as-is |

On success the bot replies with the ID assigned by the API.

### Commands

| Command | Description |
| --- | --- |
| `/list` | All expenses, with per-currency totals |
| `/categories` | Distinct categories, each with a **Show expenses** button |
| `/search <query>` | Full-text search across expenses |
| `/category <name>` | Expenses in one category |
| `/delete <id>` | Delete, guarded by an inline Yes/Cancel keyboard |
| `/update <id>` | Update — followed by the same five expense lines |

`/update` takes the ID on the command line and the new values on the next five lines:

```
/update 12
250 EUR
Train ticket
Travel
CFR
2026-09-05
```

Replies are sent with HTML parse mode, and every value that comes from a chat message
or from the API is HTML-escaped before it is inserted. If Telegram still rejects the
markup, the bot retries the same message as plain text. Replies longer than Telegram's
4096-character limit are split at blank-line boundaries so no expense block (and no HTML
tag) is cut in half.

`/categories` attaches an inline keyboard with one **Show expenses** button per category;
tapping one lists that category's expenses in a new message, so the keyboard stays available
for the next tap. The category travels in the button's callback data behind a `SHOW_CAT_`
prefix. Telegram caps callback data at 64 UTF-8 bytes, so a category whose name does not fit
is still listed but gets no button, with `/category <name>` offered instead; the keyboard is
also capped at 50 buttons.

Lists are rendered as one two-line block per expense:

```
📋 All expenses

📊 161.50 EUR · 4 items · ↩️ 12.00 refunded

100.00 EUR — Groceries
🏷 Food · 👤 Lidl · 📅 2026-09-01 · 🆔 106

42.50 EUR — Coffee beans
🏷 Food · 👤 Roastery · 📅 2026-09-02 · 🆔 107
```

The id is monospaced so it can be tapped to copy for `/delete` and `/update`. Refunds are
marked with ↩️, and metadata fields that the API returns empty are omitted.

## Tests

```
mvn test
```

JUnit 5 unit tests cover the pure helpers in `TelegramBot` — HTML escaping, amount
formatting, the two-line expense block, the full list reply, message splitting and the
five-line payload validation. These helpers are `static` and package-private for that
reason, so the tests need neither a bot instance nor a Telegram connection.

| Test | Covers |
| --- | --- |
| `ExpenseFormattingTest` | `escape`, `formatAmount`, `itemCount`, `formatExpenseItem`, `formatExpenseResponse` |
| `MessageSplittingTest` | `splitForTelegram` — chunk size, block boundaries, no content loss |
| `ExpenseParsingTest` | `parseExpense` — the five-line payload, for both a new expense and `/update` |

## Backend API

`ExpenseWebClient` expects a REST service under `<API_BASE_URL>/api/v1`:

| Method | Path | Used by |
| --- | --- | --- |
| `POST` | `/expenses` | Adding an expense |
| `GET` | `/expenses` | `/list` |
| `GET` | `/expenses/{id}` | `/delete` confirmation prompt |
| `PATCH` | `/expenses/{id}` | `/update` |
| `DELETE` | `/expenses/{id}` | `/delete` confirmation |
| `GET` | `/expenses/categories` | `/categories` |
| `GET` | `/expenses/by-category?category=` | `/category`, the **Show expenses** buttons |
| `GET` | `/expenses/by-description?description=` | (client method, not wired to a command) |
| `GET` | `/expenses/search?q=` | `/search` |

Collection endpoints return an `ExpenseResponse`: a list of `expenses` plus a list of
per-currency `sums` (`sum`, `refundSum`, `count`). Any non-2xx response is turned into a
`RuntimeException` carrying the status code and body, which the bot reports back in chat.

## Project layout

```
Dockerfile                       multi-stage build (maven:temurin-26 -> temurin-26-jre-alpine)
docker-compose.yml               single bot service, host-gateway mapping to the local API
.env.example                     template for the required environment variables
src/main/java/eeu/test/
├── Main.java                    entry point — loads Config, registers the bot
├── Config.java                  environment-variable configuration, validated at startup
├── TelegramBot.java             update handling, command routing, message parsing/formatting
├── Expense.java                 parsed expense from a chat message
├── Result.java                  success/error wrapper returned by the parser
└── client/
    ├── ExpenseWebClient.java    java.net.http client for the expense REST API
    ├── ExpenseItem.java         a persisted expense (has an id, amount as double, isRefund)
    ├── ExpenseResponse.java     list response: expenses + per-currency sums
    └── ExpenseSummary.java      per-currency totals
```

## Dependencies

- [telegrambots](https://github.com/rubenlagus/TelegramBots) 6.0.1 — long-polling bot API
- Jackson 2.17.0 — JSON serialization
- Lombok 1.18.48 (provided) — `@Data`, constructors

> [!NOTE]
> Lombok is declared under `<annotationProcessorPaths>` in `pom.xml`. JDK 23 and later no
> longer run annotation processors implicitly, so without that the build fails with
> "cannot find symbol: method getDescription()" and similar.

## Known limitations

- Amounts are parsed as integers, so `12.50 RON` is rejected; the API model stores them
  as doubles.
- Dates are not validated — whatever is on line 5 is sent to the API verbatim.
- Refunds are always sent as `false`; there is no command to mark one.
- No tests.
