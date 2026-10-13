# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

- `agent-harness-build/` — the Python reference implementation (Tech With Tim's "build your own agent harness" project). It is its own nested git repo. **Do not modify anything in this folder** — it exists only as the reference to study while porting.
  - `v0.py` → `v4.py` — five incremental tutorial stages (see Architecture below).
  - `harness.py` — the final version that merges everything from v0–v4 into one program.
  - `mcp_servers.json` — MCP server launch config used by `v3.py`/`harness.py`.
  - `memory.md` — the persistent memory file read/written by `v4.py`/`harness.py`.
  - `workspace/` — sandbox folder the `list_files`/`read_file`/`write_file` tools operate on.
  - `pyproject.toml` / `.python-version` — Python 3.13, deps: `openai`, `mcp`, `pydantic`.
- `agent-harness-java/` — the active Java/LangChain4j port, built with Maven, as a sibling to `agent-harness-build/`.
  - `pom.xml` — Java 25, `dev.langchain4j:langchain4j-bom:1.22.0` imported for version alignment, deps on `langchain4j` (core), `langchain4j-open-ai`, `langchain4j-mcp` (pinned separately — see note below), and `jackson-databind` (for parsing tool-call arguments).
  - `workspace/` — sandbox folder `list_files`/`read_file`/`write_file`/`rename_file` operate on, same role as the Python project's `workspace/`.
  - `src/main/java/com/agentharness/chat/ChatProvider.java` — the model registry (see below).
  - `src/main/java/com/agentharness/v0/V0.java` — the v0 stage.
  - `src/main/java/com/agentharness/v1/V1.java` — the v1 stage.
  - `src/main/java/com/agentharness/v2/V2.java` — the v2 stage.
  - `src/main/java/com/agentharness/v3/V3.java` — the v3 stage.
  - `src/main/java/com/agentharness/v4/V4.java` — the v4 stage.
  - `memory.md` — v4's runtime memory store (gitignored; generated, not source).

## Commands (Python reference, run from inside `agent-harness-build/`)

- Install deps: `uv sync`
- Run a stage: `uv run v0.py` (and `v1.py` … `v4.py`, `harness.py`)
- `v0`–`v2`, `v4`, and `harness.py`'s `local`/`local-small` models expect an OpenAI-compatible server at `http://localhost:11434/v1` (Ollama) serving `qwen3.5:4b` / `qwen3.5:2b`.
- `v3.py` and `harness.py` additionally launch MCP servers as subprocesses via `uvx` (`mcp-server-time`, `mcp-server-fetch`) — `uvx` must be on PATH.
- `harness.py`'s `gpt` and `claude` model entries read `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` from the environment.
- No test suite or linter is configured in this repo.

## Architecture of the Python reference

Every stage shares the same shape: a `TOOLS`/`LOCAL_TOOLS` dict mapping tool name → Python function, a `TOOL_SCHEMAS` list of OpenAI function-calling JSON schemas describing those tools to the model, and a loop that calls the model, executes any requested tool calls, appends their results to `messages`, and repeats until the model replies without `tool_calls`. Each version adds exactly one capability on top of the last:

- **v0 — bare LLM call.** One `client.chat.completions.create(...)` per user turn, no tools, no loop.
- **v1 — tool calling (single round).** Adds `list_files`/`read_file` as real Python functions plus JSON schemas. If the model requests a tool, it's run once and the result is fed back for one final reply — not a loop yet.
- **v2 — the agentic loop.** The real trick: v1's single round becomes a `while True` loop that keeps calling the model and executing tool calls until it gets a plain-text answer. Adds `write_file`.
- **v3 — MCP as a tool source.** Connects to external MCP servers (`time`, `fetch`) over stdio (`AsyncExitStack` + `stdio_client` + `ClientSession`), and merges each server's advertised tools into the same `TOOL_SCHEMAS` list the local tools use. `call_tool()` dispatches by name to either a local function or the MCP `ClientSession` that owns it. The loop becomes async.
- **v4 — persistent memory.** `memory.md` is loaded into the system prompt at startup; a `save_memory` tool appends new facts to it. The `messages` conversation list now persists across turns instead of being rebuilt per call.
- **harness.py — everything merged**, plus a model registry (`MODELS: dict[str, ModelConfig]`, each a `base_url`/`api_key`/`model`) so you can switch between local Ollama models and cloud GPT/Claude at runtime, and slash commands (`/models`, `/model <name>`, `/tools`, `/memory`, `/quit`) handled in `handle_command()`. These are referred to in-code as the four pillars: model registry, memory, MCP, tools.

## Commands (Java port, run from inside `agent-harness-java/`)

- Compile: `mvn -q -DskipTests compile`
- Run a stage: `mvn -q exec:java -Dexec.mainClass=com.agentharness.v4.V4` (substitute `com.agentharness.v0.V0`, `v1.V1`, `v2.V2`, `v3.V3`, etc.; the `exec-maven-plugin` default `mainClass` in `pom.xml` tracks the newest stage, so plain `mvn exec:java` runs that one)
- `ChatProvider.DEFAULT_MODEL` currently points at a `groq-*` entry, which requires `GROQ_API_KEY` in the environment — `ChatProvider.get(...)` throws `IllegalStateException` at call time if it's unset. A `local`/`local-qwen` entry (Ollama) needs no key but needs Ollama running locally instead.
- v3 additionally launches MCP servers as subprocesses via `uvx` (`mcp-server-time`, `mcp-server-fetch`) — `uvx` must be on PATH, same as the Python reference.
- No test suite is configured yet.

## Architecture of the Java port

The port is built up the same way the Python version was taught: one component at a time, in the same order (bare LLM call → tool calling → agentic loop → MCP → memory → full harness), rather than one big rewrite. Each stage lands as its own, separately reviewable addition before the next is started.

- `agent-harness-build/` stays untouched; treat it strictly as reference material to compare against.
- **Model access goes through `ChatProvider`, not direct `OpenAiChatModel` construction.** It's the Java analog of `harness.py`'s `MODELS: dict[str, ModelConfig]` registry, introduced early rather than only at the final harness stage: a `Map<String, ModelConfig>` of `(baseUrl, apiKey, modelName)` keyed by a short name, with `ChatProvider.get(name)` building a fresh `OpenAiChatModel` pointed at that config. Both Groq (`groq-llama-70b`, `groq-llama-8b`, `groq-oss-120b`, `groq-oss-20b`) and Ollama (`local` = llama3.2:1b, `local-qwen` = qwen3:8b) expose an OpenAI-compatible endpoint, so cloud and local models are configured identically — the only difference is the base URL and whether the API key is real (`GROQ_API_KEY`) or a required-but-ignored placeholder (`"ollama"`; Ollama's OpenAI-compatible path is `/v1`, **not** `/api`, which is its differently-shaped native API). `ChatProvider.DEFAULT_MODEL` is what stage classes use unless told otherwise; `llama3.2:1b` is fast but unreliable at generating correctly-shaped tool calls, `qwen3:8b` is more reliable but too slow without GPU acceleration — Groq is the practical default for actually exercising later stages. No code outside `ChatProvider` should construct a chat model directly.
- **v0 — bare LLM call** (`v0/V0.java`). Equivalent to `v0.py`: a REPL loop that builds a `SystemMessage` + `UserMessage` pair, calls `ChatModel.chat(...)`, and prints `response.aiMessage().text()`. No tools, no loop beyond the REPL itself.
- **v1 — tool calling, single round** (`v1/V1.java`). Equivalent to `v1.py`: adds `list_files`/`read_file` as plain Java methods plus `ToolSpecification`s (built with `JsonObjectSchema`), sent via `ChatRequest.builder().toolSpecifications(...)`. If `aiMessage.hasToolExecutionRequests()`, each `ToolExecutionRequest`'s JSON `arguments()` string is parsed with Jackson into a `Map<String, Object>`, dispatched through a `Map<String, Function<Map<String,Object>, String>> TOOLS` table (the Java analog of Python's `TOOLS` dict), and the result fed back as a `ToolExecutionResultMessage` for exactly one final reply. Not a loop (that's v2): the code itself only ever makes one follow-up call.
- **v2 — the agentic loop** (`v2/V2.java`). Equivalent to `v2.py`: v1's single round becomes a `while (true)` loop in `runAgent()` — keep calling the model and executing whatever `ToolExecutionRequest`s come back, appending `ToolExecutionResultMessage`s, until a response has no tool requests. Adds `write_file` alongside `list_files`/`read_file`, plus `rename_file` (`Files.move`, refuses to overwrite an existing target) — an extra beyond the Python original, added by request.
- **Caveat that shaped v1 and v2 (Groq-specific, not in the Python original):** some Groq models — `gpt-oss-20b`/`120b` in particular, which use an agentic "harmony" format — keep trying to call a tool even when a request doesn't declare any `toolSpecifications`. Groq's gateway renders "no tools offered" as `tool_choice: none` and hard-rejects the mismatch with `tool_use_failed` if the model calls one anyway, instead of silently ignoring it the way Ollama's qwen does in the Python reference. **Rule of thumb: once a conversation has used tools, every subsequent call in it should keep declaring the same `toolSpecifications`, even on a call that isn't expected to use them** — never drop to a bare `model.chat(messages)`/no-tools call mid-conversation. v2's loop satisfies this naturally (it always declares the tools); v1's final call does too, specifically to work around this. Also defend tool implementations against malformed/hallucinated arguments (e.g. a wrong key name) rather than assuming the model always calls with a valid schema — tool functions across v1–v3 return an `"error: ..."` string instead of throwing when a required argument is missing or the wrong type.
- **v3 — MCP as a tool source** (`v3/V3.java`). Equivalent to `v3.py`: launches `mcp-server-time`/`mcp-server-fetch` as subprocesses via the `langchain4j-mcp` module (`StdioMcpTransport` + `DefaultMcpClient`), lists each server's tools with `McpClient.listTools()`, and merges them into the same `List<ToolSpecification>` the local tools (`list_files`/`read_file` only here, no `write_file` — matches v3.py) use. `callTool()` dispatches by name to either a local function or `McpClient.executeTool(request)`, extracting text from the returned `ToolExecutionResult.resultContents()`. The agent loop itself is unchanged from v2.
  - **Two fixes needed for the official Python MCP servers specifically** (neither applies to Java-based MCP servers): (1) `DefaultMcpClient.builder().protocolVersion("2025-11-25")` — these servers only speak the legacy protocol and don't answer the client's newer auto-detection probe, which otherwise stalls for the full `initializationTimeout` (30s default). (2) `StdioMcpTransport.builder().environment(Map.of("PYTHONUNBUFFERED", "1"))` — Python block-buffers stdout when it isn't a TTY, so a server's JSON-RPC responses get computed but never actually flushed down the pipe until the buffer fills or the process exits; every call hangs until timeout without this. Both were diagnosed by testing `uvx mcp-server-time` directly over stdin/stdout outside the Java client, not guessed.
- **`langchain4j-mcp` version is pinned separately from the BOM.** It has never reached a GA version number (tracks langchain4j's version but stays `-betaNN`-suffixed), so `langchain4j-bom` doesn't cover it; `pom.xml` pins it explicitly (`1.22.0-beta32` as of this writing).
- **v4 — persistent memory** (`v4/V4.java`). Equivalent to `v4.py`: `memory.md` (project root, gitignored) is loaded into the system prompt (`%s` placeholder, filled via `String.formatted(...)`) once at startup; a `save_memory` tool appends new facts to it (`Files.writeString(..., CREATE, APPEND)`, always-append — no dedup, matching the Python original). Local tools are `list_files`/`read_file`/`save_memory` only (no `write_file`, no MCP — matches v4.py). The bigger structural change: `messages` is now built **once** in `main()` and passed into `runAgent()` by reference, which mutates and keeps appending to the same list across every REPL turn — v0–v3 each built a fresh `messages` list per call and discarded it. Concretely, `runAgent()` now appends the `AiMessage` to `messages` unconditionally (both the tool-call and final-answer branches), whereas in v1–v3 the final answer was only ever returned, never persisted, since there was nothing to persist it into. Verified across two separate process runs (not just one session): saving a fact in one run and asking "what do you remember about me?" in a fresh process afterward correctly recalled it, confirming the file-based persistence actually works end to end, not just in-memory within a single run.
- v4 is the last incremental stage; `harness.py`'s merge of all four pillars (model registry + memory + MCP + tools) plus its model-switching slash commands is not ported yet.
