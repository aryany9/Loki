# Empirical NPU Graph Context Spike Report (SM8750 + Qualcomm E2B)

**Date**: 2026-09-05  
**Device**: Samsung Galaxy S25 / SM8750 (`SM-S931B`, Snapdragon 8 Elite, HTP v79)  
**Model**: Qualcomm E2B multimodal on-device package  

## Spike Findings

1. **Native Engine Initialization**:
   - `maxNumTokens = 1280`: Success (KV=1280, init time: ~1930ms).
   - `maxNumTokens = 2048`: Success (KV=2048).
   - `maxNumTokens = 4096`: Success (KV=4096, init time: ~2534ms).
   - `maxNumTokens = 8192`: Success (KV=8192, init time: ~715ms).

2. **Analysis**:
   - The Qualcomm QNN AOT graph on HTP v79 supports dynamic context configurations up to 4096 / 8192 tokens.
   - The initial conservative guess of 1280 tokens caused severe token-budget exhaustion when combined with non-compact schemas (~540 tokens) and audio inputs (~100 tokens), causing the KV compaction guard to trip on every single turn.
   - Setting `NPU_DEFAULT_KV_CAPACITY = 4096` ensures ample headroom for 5+ turn multi-tool interactions without compaction thrashing.
   - Combined with compact tool schemas (<= 150 tokens) and compact system instruction (<= 50 tokens), the per-turn budget on NPU operates smoothly within the graph capacity.

3. **Resolution**:
   - Set `LiteRtLlmEngine.NPU_DEFAULT_KV_CAPACITY = 4096`.
