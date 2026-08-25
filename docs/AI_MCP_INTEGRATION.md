# AI + MCP Integration

This branch (`ai-mcp-integration`) adds a pluggable AI layer to Sketchware
Pro: multiple AI providers, secure key storage, AI custom blocks, natural
language → blocks generation, and a desktop MCP server that can operate on
Sketchware project folders.

## Architecture

```
Settings (AI Provider)
        │ API key → AiSecureStore (EncryptedSharedPreferences, fallback plain)
        ▼
AiProviderRegistry ──► AiProvider interface
        ├── GeminiProvider            (generativelanguage REST via OkHttp)
        └── OpenAiCompatibleProvider  (chat/completions; OpenAI/OpenRouter/local)
        │
GeminiApiService  (facade: generateText / chat, fully async)
        │
GeminiNLToBlocksConverter ── NLPlanValidator (strict schema v1) ── BlockCatalog
        │                                        only catalog-verified ops pass
        ▼
NLPlanToBlocks ── real com.besome.sketch.beans.BlockBean graph
        │
LogicEditorActivity ("Generate from text") ── pane insertion + persistence
```

### Custom AI blocks

Registered in `pro.sketchware.ai.blocks.AiExtraBlocks` and hooked into
`mod.hilal.saif.blocks.BlocksHandler.builtInBlocks`, so they behave exactly
like built-in extra blocks:

| Block | Kind | Spec |
|---|---|---|
| `aiGeminiGenerate` | command | `AI Gemini generate text prompt %s then` |
| `aiResponse` | returns String | `AI last response` |
| `aiChatSend` | command | `AI chat send conversation %s then` |

The generated code templates are self-contained (android.jar APIs only:
`HttpURLConnection` + `org.json`), run on a background thread and continue the
nested stack on the UI thread. Generated apps read their key from their own
`sketchware_ai_config` SharedPreferences and store the last response there.
**Generated projects need the INTERNET permission** (Permission Manager).

### Natural language → blocks

1. `GeminiNLToBlocksConverter` builds a constrained prompt containing the
   operation catalog (never the whole project).
2. The model replies with JSON plan `{version:1, events:[...], warnings:[]}`.
3. `NLPlanValidator` strictly validates ops/params/substacks against
   `BlockCatalog`; unknown ops are rejected with a repair round.
4. `NLPlanToBlocks` maps the plan to real linked `BlockBean`s which are
   inserted into the live pane and persisted through the editor's normal save
   path.

### MCP server

See [`mcp-server/README.md`](../mcp-server/README.md). Desktop-only module;
grouped tools (`project.*`, `view.*`, `source.*`, `manifest.*`,
`snapshot.*`, ...) operate on real `.sketchware` files with a workspace
boundary, atomic writes + backups, and read-only-by-default permissions.

## Secure key storage

* AndroidX Security `EncryptedSharedPreferences` (AES256-GCM master key).
* Graceful fallback to plain prefs when the Keystore is broken — surfaced in
  Settings as a warning.
* Keys never logged, never shown in full after saving (masked previews only).
* No keys are ever committed or embedded in generated projects by default.

## Testing

* `app`: unit tests for the NL pipeline under `app/src/test/java/...`
* `mcp-server`: JUnit tests for catalog validation, XML ops, workspace safety.

CI (`.github/workflows/build-apk.yml`) builds the debug APK and the MCP fat
jar on every push to this branch and uploads both as artifacts.

## Limitations

* NL plans currently support literal parameters (strings/numbers/booleans);
  nested expression-block parameters are planned.
* The MCP server cannot trigger device-side Gradle builds (`build.run`
  documents this); it validates and edits project data instead.
* Logcat access is limited to log files inside the workspace boundary.
