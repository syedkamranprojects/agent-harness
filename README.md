# Agent Harness

A from-scratch AI agent harness — built step by step, with no agent framework doing the work for you, to actually understand what an "agent" is made of: a model call, tool calling, the agent loop, MCP, and memory.

This repo currently holds a **Java / [LangChain4j](https://docs.langchain4j.dev/) port** of that harness, built incrementally to mirror a Python original one concept at a time.

## Where this came from

The core ideas and the step-by-step structure (v0 → v4) are from Tech With Tim's YouTube tutorial, **["How to Build AI Agents in Python - Full Tutorial - Building a Harness"](https://www.youtube.com/watch?v=H5o1P8RMiMw)**. It builds a full agent harness in Python from scratch — no LangChain, no CrewAI, no framework — to show the actual mechanics that make an agent an agent, growing the implementation one file at a time: `v0.py` → `v4.py`, then a merged `harness.py`.

That Python reference code (the video's own companion code, from [aiagentbuilders.co](https://aiagentbuilders.co/yt)) lives locally in `agent-harness-build/` as study material — it's gitignored here since it's the tutorial's own code, not part of this port, and it's kept completely unmodified throughout.

The actual work in this repo is the **Java/LangChain4j port** of that same harness, in `agent-harness-java/`, built the same way the tutorial teaches it: one component added at a time, each one runnable and testable before the next is layered on.

## What's in this repo

- **`agent-harness-java/`** — the Java port. See its stages below.
- **`CLAUDE.md`** — guidance for AI coding agents (e.g. Claude Code) working in this repo: commands, architecture notes, and the quirks discovered while porting.
- `agent-harness-build/` — the Python reference, present locally but not tracked in this repo.

## The stages

Each stage is its own class under `agent-harness-java/src/main/java/com/agentharness/`, adding exactly one capability on top of the last — same progression as the Python tutorial:

| Stage | Adds | Python equivalent |
|---|---|---|
| `v0/V0.java` | A bare LLM call. No tools, no loop. | `v0.py` |
| `v1/V1.java` | Tool calling — `list_files`/`read_file`, one round only. | `v1.py` |
| `v2/V2.java` | The real agentic loop: keep calling the model and running tools until it just answers. Adds `write_file` and `rename_file` (an extra beyond the tutorial). | `v2.py` |
| `v3/V3.java` | MCP as a tool source — launches `mcp-server-time`/`mcp-server-fetch` as subprocesses and merges their tools in alongside the local ones. | `v3.py` |
| `v4/V4.java` | Persistent memory — a `memory.md` file loaded into the system prompt, with a `save_memory` tool to add to it; conversation history now persists across turns. | `v4.py` |

`ChatProvider` (`chat/ChatProvider.java`) is a small model registry used from `v0` onward, supporting both cloud models via [Groq](https://groq.com/) (OpenAI-compatible API) and local models via [Ollama](https://ollama.com/) — switching models is a one-line change, never a rewrite.

See `CLAUDE.md` for the full architecture notes, including the Groq/MCP-specific issues hit (and fixed) while building this.

## Getting started

Requirements: JDK 25, Maven, and either a [Groq API key](https://console.groq.com/) or a local [Ollama](https://ollama.com/) install.

```bash
cd agent-harness-java

# cloud (Groq) — needs GROQ_API_KEY
export GROQ_API_KEY=your_key_here

# or local — needs `ollama serve` running with a model pulled, e.g.:
#   ollama pull llama3.2:1b

mvn -q -DskipTests compile
mvn -q exec:java                                              # runs the newest stage
mvn -q exec:java -Dexec.mainClass=com.agentharness.v1.V1       # or a specific earlier stage
```

Which model is used is set by `ChatProvider.DEFAULT_MODEL` — swap the key (e.g. `"groq-oss-20b"`, `"local"`, `"local-qwen"`) to switch between cloud and local without touching anything else.
