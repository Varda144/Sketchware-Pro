# Contributing

Thanks for contributing to Sketchware Pro!

## Setup (Android Studio)

1. Install **Android Studio** (Ladybug or newer) with **JDK 17**.
2. Clone your fork: `git clone https://github.com/<you>/Sketchware-Pro.git`
3. Open the project in Android Studio and let Gradle sync
   (`gradle/libs.versions.toml` pins all versions).
4. Build/install the debug variant:
   `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

The debug build uses a mock `google-services.json` generated automatically;
no Firebase secrets are required.

## Building & testing

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # AI pipeline unit tests
./gradlew :mcp-server:test            # MCP server tests
./gradlew :mcp-server:fatJar          # runnable MCP server jar
```

CI on this branch: `.github/workflows/build-apk.yml` builds the debug APK +
MCP jar and uploads them as artifacts on every push.

## Testing the Gemini/AI features

1. Install the debug APK and open **Settings → AI Provider**
   (launched with fragment tag `settings_ai`).
2. Choose a provider (Google Gemini, or any OpenAI-compatible endpoint),
   paste **your own API key**, optionally set a model name, press **Save**,
   then **Test connection**. The key is stored in
   `EncryptedSharedPreferences`; only a masked preview is ever shown again.
3. In any project, open an event's logic editor → ⋮ menu → **Generate from
   text**, describe behavior in plain language, and insert the generated
   blocks.
4. The custom blocks **AI Gemini generate text prompt … then**,
   **AI last response** and **AI chat send conversation … then** appear in the
   *My Block* palette and work like native blocks in built apps. Generated
   apps need their own key (see `docs/AI_MCP_INTEGRATION.md`) and the
   INTERNET permission.

Never commit API keys. Keys are read from encrypted storage (app) or
environment variables (`GEMINI_API_KEY` for the MCP server) only.

## Building & running the MCP server

```bash
java -jar mcp-server/build/libs/mcp-server-1.0.0-all.jar \
  --workspace /path/to/.sketchware --port 8080
```

Read-only by default; add `--allow-write` (and `--allow-destructive` for
delete/restore). Full tool list and client examples:
[`mcp-server/README.md`](mcp-server/README.md).

## Testing MCP tools

```bash
curl -s localhost:8080/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
curl -s localhost:8080/mcp -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"project.list","arguments":{}}}'
```

## Pull requests

* Keep changes minimal and isolated; follow existing package conventions
  (`pro.sketchware.*`, `mod.*`, ...).
* New features must degrade gracefully without keys/network.
* No UI-thread network calls; no secrets in source or logs.
