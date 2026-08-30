# Spike 2 Validation Report: llama.cpp + GBNF Tool Calling on ARM64

**Date**: 2026-08-24  
**Device**: Samsung Galaxy S25 / `SM-S931B` (ARM64, Android 16)  
**Model**: `Qwen3.8-4B-Q4_K_M.gguf` (2.58 GiB)  
**Status**: **PASSED (100% Valid Grammar / Tool-Calling Verified)**

---

## 1. Objectives & Results

| Requirement | Target | Achieved | Status |
|---|---|---|---|
| **Native Integration** | llama.cpp compiled via NDK 26 / CMake | `liblokillama.so` linked with `llama`, `llama-common`, `ggml` | **PASS** |
| **Model Loading** | GGUF model loaded directly from app storage | Loaded in ~1.8s (`CPU_Mapped model buffer size = 2572.86 MiB`) | **PASS** |
| **GBNF Constraint** | Valid JSON schema enforced during generation | Built-in `llama.cpp` schema-to-grammar compiler | **PASS** |
| **Tool-Calling Output** | Exact tool name & argument parameters output | Structured JSON with tools: `call_contact`, `dial_number`, `open_app`, `get_battery_status`, `get_current_time`, `media_control` | **PASS** |
| **KV Cache Management** | Consecutive generations without OOM | `llama_memory_clear()` applied before each prompt | **PASS** |

---

## 2. Benchmark Sample Outputs

### Example 1: `call_contact`
- **Prompt**: `"Call Rahul"`
- **Generated**:
```json
{
  "tool": "call_contact",
  "arguments": {
    "name": "Rahul"
  }
}
```

### Example 2: `dial_number`
- **Prompt**: `"Dial 9876543210"`
- **Generated**:
```json
{
  "tool": "dial_number",
  "arguments": {
    "number": "9876543210"
  }
}
```

### Example 3: `open_app`
- **Prompt**: `"Open YouTube Music"`
- **Generated**:
```json
{
  "tool": "open_app",
  "arguments": {
    "name": "YouTube Music"
  }
}
```

### Example 4: `media_control`
- **Prompt**: `"Skip to the next song"`
- **Generated**:
```json
{
  "tool": "media_control",
  "arguments": {
    "action": "next_track"
  }
}
```

---

## 3. Key Architecture & Engineering Findings

1. **Native Release Compilation is Critical**:
   - Compiling native code in Debug mode on Android disables ARM NEON vectorization, causing 50x slowdowns.
   - Forcing `-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16` in CMake gave real-time token throughput on physical ARM64 hardware.
2. **Schema-to-Grammar Integration**:
   - Linked `llama-common`'s native `json-schema-to-grammar` directly in C++, ensuring 100% compliance with `llama_sampler_init_grammar`.
3. **Sampler State Machine & KV Cache**:
   - `llama_sampler_sample()` automatically advances the grammar state machine internally.
   - Resetting KV memory (`llama_memory_clear(llama_get_memory(ctx), true)`) between distinct turns prevents context exhaustion across multi-prompt sessions.
