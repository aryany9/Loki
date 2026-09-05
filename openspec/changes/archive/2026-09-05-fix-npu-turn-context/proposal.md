# Proposal: fix-npu-turn-context

## Why

Post-implementation device testing of `add-npu-backend-support` exposed a conversation-context
regression. On NPU (fixed 1024-token KV), the KV-overflow guard in `LiteRtLlmEngine.generate()`
resets the conversation via `createConversation()` **without config or history replay**: the
model loses its system instruction AND all dialogue memory mid-chat (observed: follow-up "mom"
answered with a generic greeting). Compounding this, per-turn prompts re-inject ~1.8k chars of
tool schemas and the default `maxOutputTokens` was raised 256→512 (unrelated to any OpenSpec
change), tripping the reset guard after roughly one turn.

## What Changes

- **Context-preserving KV reset** (`LiteRtLlmEngine`): when the KV budget is near exhaustion,
  the engine re-creates the conversation **with the original `AgentConfig`** (system instruction
  preserved) and replays the most recent turns (`ConversationContext`) up to a replay budget —
  never a blank conversation.
- **Backend-aware output budget** (`ConversationSession`): `maxOutputTokens` default becomes
  backend-aware — 256 on NPU (restoring the previous default), 512 elsewhere; the unexplained
  blanket 512 is reverted.
- **Compact tool prompting** (`ConversationSession`): full tool schemas are injected once per
  conversation (first turn / after any conversation reset), not on every follow-up turn;
  follow-up turns carry only the user message + minimal turn context.
- **Parser fallback observability** (`ToolCallParser`): natural-language/truncation fallbacks
  log when they fire (diagnosability; behavior unchanged).

## Capabilities

### Modified Capabilities
- `llm-engine`: KV-overflow handling must be context-preserving (re-init with config + replay),
  not context-destructive.
- `conversation-manager`: per-turn prompt construction must not re-inject full tool schemas on
  follow-up turns; output token default is backend-aware.

## Impact
- `core/llm` (`LiteRtLlmEngine.generate()` reset path, needs `AgentConfig`/context retained on
  engine), `core/conversation` (`ConversationSession` prompt construction, maxTokens),
  `ToolCallParser` logging. No data migration. NPU and GPU/CPU paths both benefit; NPU is where
  the regression was observable.
