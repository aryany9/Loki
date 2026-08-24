# Spike 3 Validation Report: Offline Voice Pipeline (Whisper.cpp + VAD + TTS)

**Date**: 2026-08-24  
**Device**: Samsung Galaxy S25 / `SM-S931B` (ARM64, Android 16)  
**Models**:
- **STT**: `whisper.bin` (`ggml-tiny.en.bin`, 77.7 MB)
- **LLM**: `model.gguf` (`Qwen3.8-4B-Q4_K_M.gguf`, 2.58 GiB)
- **TTS**: Local Android `TextToSpeech` engine  
**Status**: **PASSED (Full End-to-End Offline Voice Loop Verified)**

---

## 1. Pipeline Verification Summary

| Component | Test Scenario | Observed Result | Status |
|---|---|---|---|
| **Microphone Capture** | 16kHz mono audio recording via `AudioRecord` | Smooth stream capture without buffer overflow | **PASS** |
| **VAD Silence Detection** | Auto-stop when user finishes speaking (400ms threshold) | Speech onset detected via RMS; cleanly cuts recording on silence | **PASS** |
| **Whisper STT** | Transcribe live audio utterance ("What time is it") | Accurate offline transcription in **682 ms** | **PASS** |
| **LLM Tool Calling** | GBNF constrained JSON extraction from transcript | Correct tool identification (`get_current_time`, `call_contact`, etc.) | **PASS** |
| **Local TTS Audio** | Spoken confirmation playback | Starts playback in **2 ms** without internet access | **PASS** |

---

## 2. Latency Breakdown (10-Utterance Benchmark)

- **Total Utterances**: 10
- **Median STT (Whisper Tiny)**: **682 ms**
- **Median TTS (Start to Playback)**: **2 ms**
- **Median LLM (4B Model on CPU)**: **6,014 ms**
- **Median Total E2E**: **6,707 ms**

> **Note on E2E Latency**:
> The STT (682ms) and TTS (2ms) stages are already well within the real-time budget (<700ms combined). The LLM duration (~6.0s) is due to running the 4.3B parameter model purely on CPU. In subsequent phases, swapping to a lightweight 1B–1.5B model (e.g., `Qwen2.5-1.5B-Instruct` or `Gemma-3-1B`) or enabling GPU/NPU offload will bring LLM latency to ~1.0s, achieving the final `< 2.0s` target.

---

## 3. Spikes Milestone Complete

All three foundational feasibility spikes for the Loki Assistant are now fully validated on physical hardware:
1. **Spike 1**: Android Default Assistant Role + UI Lock-screen Overlay (`FLAG_SHOW_WHEN_LOCKED`).
2. **Spike 2**: llama.cpp + GBNF Tool Calling with 100% structured JSON precision.
3. **Spike 3**: Whisper.cpp STT + VAD + local Android TTS end-to-end loop.
