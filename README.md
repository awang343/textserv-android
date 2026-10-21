# Textserv (Android)

Default SMS/MMS app that mirrors every message to the textserv server in `../server`
and sends messages the server asks it to.

## Build & install

```sh
./gradlew installDebug          # phone connected via adb
./gradlew testDebugUnitTest     # MMS PDU codec tests
```

## Server

```sh
cd ../server && cargo build --release   # binary: target/release/textserv
```

Configured by `~/.config/textserv/config.toml` (written with defaults on first run;
`--config <path>` to use another file):

```toml
host = "0.0.0.0"
port = 8787
token = "change-me"                                  # omit to disable auth
log_level = "info"                                   # error | warn | info | debug | trace
db_path = "~/.local/share/textserv/textserv.db"      # default
attachments_dir = "~/.local/share/textserv/attachments"  # default
```

## Setup on the phone

1. Open Textserv → **Set as default** (required for MMS and for writing messages).
2. Allow contacts, notifications and phone-number permissions.
3. Settings → server URL (e.g. `http://100.x.y.z:8787`) and token → **Save & test**.
   History import starts automatically; progress shows under *Sync*.
4. Install a UnifiedPush distributor (e.g. ntfy) → Settings → **Set up push**.

## How it talks to the server

| Phone event | Server call |
|---|---|
| SMS/MMS received, or sent from the phone UI | `POST /api/attachments` (each MMS part) then `POST /api/log` with `phone_ref` (and `read` if already read) |
| History import / catch-up | `POST /api/known_refs` then `POST /api/log/batch` |
| Thread read here | `POST /api/read_refs` with the refs just read, then as below |
| Push `{"type":"read"}`, or app start / sync | `POST /api/read_refs/query` with the unread refs → mark the ones read elsewhere read |
| Push `{"type":"delete"}`, or app start / sync | `GET /api/deletes` → delete those messages here → `POST /api/deletes/done` |
| Any other push | `GET /api/outbox` → `POST /api/messages/:id/status {"status":"sending"}` (claim) → send → status `sent`/`failed` with `phone_ref` |
| New UnifiedPush endpoint | `POST /api/register` |

Thread keys (`address`) are the other participants' numbers in E.164, sorted and
comma-joined, e.g. `+15551110000,+15552220000` for a group. Clients calling
`/api/send` should use E.164 so their sends land in the same thread as the phone's logs.
