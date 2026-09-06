## ADDED Requirements

### Requirement: Modular Prompt Assembly by Modality
The prompt assembly pipeline SHALL construct system prompts from discrete `PromptSection` modules governed by `ConversationMode` (`VOICE` or `CHAT`). In `CHAT` mode, prompts SHALL enforce visual Markdown formatting and omit turn-control tool instructions. In `VOICE` mode, prompts SHALL enforce spoken-first phrasing without Markdown syntax.

#### Scenario: Chat prompt encourages Markdown and forbids ask_user
- **WHEN** a system prompt is generated for `ConversationMode.CHAT`
- **THEN** the prompt instructs the model to use Markdown and code blocks
- **AND** the prompt explicitly instructs the model not to invoke `ask_user` and to ask questions in plain text
- **AND** `ask_user` tool schema is absent from the prompt

#### Scenario: Grounded assistant persona
- **WHEN** common system foundation prompt sections are assembled
- **THEN** the prompt establishes Loki as a helpful, direct, offline AI assistant on Android
- **AND** explicitly disclaims any mythological Norse god or theatrical trickster persona

#### Scenario: Voice prompt instructs spoken phrasing
- **WHEN** a system prompt is generated for `ConversationMode.VOICE`
- **THEN** the prompt instructs concise, spoken-first language suitable for TTS
- **AND** the prompt omits code block formatting rules

### Requirement: Separation of Tool Protocol from Turn Protocol
The prompt assembly pipeline SHALL maintain distinct sections for **Tool Protocol** (syntax: how tools are invoked via JSON) and **Turn Protocol** (semantics: how turn transitions occur). In `VOICE` mode, Turn Protocol SHALL instruct that `ask_user` MUST be invoked if the task requires another user utterance before continuing. In `CHAT` mode, Turn Protocol SHALL instruct that questions be asked directly in natural conversational text, and Tool Protocol SHALL direct the model to respond directly in Markdown without JSON envelopes when no tool is needed.

#### Scenario: Voice turn protocol enforces ask_user on incomplete tasks
- **WHEN** the turn protocol section is rendered for `ConversationMode.VOICE`
- **THEN** it specifies that requiring user input demands an `ask_user` invocation rather than plain text alone

#### Scenario: Chat turn protocol enforces direct text questions
- **WHEN** the turn protocol section is rendered for `ConversationMode.CHAT`
- **THEN** it specifies that requiring user input demands direct natural text and forbids `ask_user`
- **AND** the tool protocol directs the model to output JSON only when invoking a tool, and to respond directly in Markdown without wrapping conversational answers in JSON

### Requirement: Strict Precedence and Recency Anchoring
Prompt sections SHALL be assembled in a fixed precedence hierarchy: Tier 1 (System Foundation & Safety), Tier 2 (Modality Profile & Domain Directives), Tier 3 (User Configuration: `USER_CUSTOM_INSTRUCTION` combining common and modality instructions, followed by `SCOPED_MEMORIES`), and Tier 1 Recency Anchor (`TOOL_PROTOCOL` and `TURN_PROTOCOL`). Non-overridable protocol instructions SHALL always appear as the final section before conversation turns.

#### Scenario: User instructions cannot override tool or turn protocol
- **WHEN** a user enters a custom instruction conflicting with JSON tool syntax or turn protocol
- **THEN** user instructions are framed within a subordinate preferences delimiter
- **AND** `TOOL_PROTOCOL` and `TURN_PROTOCOL` are rendered after user instructions as the final prompt blocks

