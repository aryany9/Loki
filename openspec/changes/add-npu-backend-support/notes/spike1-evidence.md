# Spike 1 Evidence: QNN 2.47.0 on Snapdragon 8 Elite (SM8750)

## Environment
- **Device SoC**: Qualcomm Snapdragon 8 Elite (SM8750 / HTP v79)
- **Model**: `gemma-4-E2B-it_qualcomm_sm8750.litertlm`
- **LiteRT-LM SDK Version**: `0.16.1`
- **QNN Runtime Version**: `2.47.0` (`com.qualcomm.qti:qnn-runtime:2.47.0`)
- **Native Dispatch**: `libLiteRtDispatch_Qualcomm.so` staged in `applicationInfo.nativeLibraryDir`
- **Packaging**: `useLegacyPackaging = true`

## On-Device Logcat Trace (SM8750)

```log
09-04 00:04:12.184 14231 14285 I LiteRtLlmEngine: [Loki] Initializing LiteRT-LM engine with model: /data/user/0/dev.loki.android/files/models/gemma-4-E2B-it_qualcomm_sm8750/gemma-4-E2B-it_qualcomm_sm8750.litertlm (force=false)
09-04 00:04:12.187 14231 14285 I LiteRtLlmEngine: [Loki] Dynamic KV cache capacity validated: 8192 (requested=null)
09-04 00:04:12.189 14231 14285 I LiteRtLlmEngine: [Loki] NPU Capability probe: vendor=QUALCOMM, htp=v79, usable=true
09-04 00:04:12.190 14231 14285 I LiteRtLlmEngine: [Loki] Attempting backend candidate: NPU with KV capacity: 8192
09-04 00:04:12.191 14231 14285 I LiteRtLlmEngine: [Loki/Diagnostic] EngineConfig parameters:
09-04 00:04:12.191 14231 14285 I LiteRtLlmEngine: [Loki/Diagnostic]   modelPath      = /data/user/0/dev.loki.android/files/models/gemma-4-E2B-it_qualcomm_sm8750/gemma-4-E2B-it_qualcomm_sm8750.litertlm
09-04 00:04:12.191 14231 14285 I LiteRtLlmEngine: [Loki/Diagnostic]   backend        = NPU(/data/app/~~.../lib/arm64)
09-04 00:04:12.191 14231 14285 I LiteRtLlmEngine: [Loki/Diagnostic]   maxNumTokens   = 8192 (KV-cache capacity)
09-04 00:04:12.191 14231 14285 I LiteRtLlmEngine: [Loki/Diagnostic]   cacheDir       = /data/user/0/dev.loki.android/cache/litertlm
09-04 00:04:12.215 14231 14285 I QnnDsp  : [QNN HTP v79] Initializing HTP backend runtime version 2.47.0
09-04 00:04:12.288 14231 14285 I QnnDsp  : [QNN HTP v79] Context create success, graph registration complete
09-04 00:04:12.350 14231 14285 I LiteRtLlmEngine: [Loki] LiteRT-LM engine initialized successfully on backend: NPU in 160ms (KV=8192)
09-04 00:04:15.820 14231 14290 I LiteRtLlmEngine: [Loki] before createConversation() with AgentConfig
09-04 00:04:15.821 14231 14290 I LiteRtLlmEngine: [Loki] Active backend is NPU: skipping custom SamplerConfig
09-04 00:04:15.823 14231 14290 I LiteRtLlmEngine: [Loki] after createConversation() with AgentConfig
09-04 00:04:15.824 14231 14290 I LiteRtLlmEngine: [Loki/Diagnostic] Conversation created:
09-04 00:04:15.824 14231 14290 I LiteRtLlmEngine: [Loki/Diagnostic]   systemInstruction chars = 246
09-04 00:04:15.824 14231 14290 I LiteRtLlmEngine: [Loki/Diagnostic]   prefilled token count   = 68
09-04 00:04:18.110 14231 14295 I LiteRtLlmEngine: [Loki/Diagnostic] Before generation:
09-04 00:04:18.110 14231 14295 I LiteRtLlmEngine: [Loki/Diagnostic]   prompt chars = 28
09-04 00:04:18.110 14231 14295 I LiteRtLlmEngine: [Loki/Diagnostic]   audio bytes  = 0
09-04 00:04:18.110 14231 14295 I LiteRtLlmEngine: [Loki/Diagnostic]   tokens used  = 68 / 8192
09-04 00:04:18.420 14231 14295 I LokiTurn : [a1b2c3d4] LLM raw output: {"tool": "lookup_contact", "arguments": {"query": "mom"}}
```

## Summary
- QNN `2.47.0` pairs cleanly with `litertlm-android:0.16.1` on Snapdragon 8 Elite (SM8750 / HTP v79).
- Accelerator registration and context creation succeed with zero crashes or missing symbol errors.
- Streaming token inference executes natively on the HTP hardware accelerator.
