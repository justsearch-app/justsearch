---
title: Inference placement reasons
type: reference
status: stable
description: Additive placement and refusal reasons for GPU planning and chat ownership.
---

# Inference placement reasons

`chat_model_unmeasured` means the selected chat artifact has no confirmed measured profile identity. Its GPU reservation is the model file plus a GPU projector, KV at the configured context and cache precision, and 770 MiB of runtime overhead. CPU chat reserves no device memory. The remaining memory continues to plan the encoders; this reason alone does not mean insufficient encoder memory.

`chat_model_missing:<path>` means the saved chat artifact is unavailable at the named path. It reserves no GPU memory, and the encoders are planned as if no chat artifact were selected. Status names the missing file and reports chat unavailable with `MODEL_LOAD_FAILED` when no server is running. A running server keeps its observed status.

`encoder_device_conflict` means an encoder was explicitly placed on a different CUDA device from the device-memory plan. That role keeps its explicit device and unplanned policy; chat and the other roles keep the shared plan. It is an additive `user_setting_unmet` cause, distinct from insufficient encoder memory.

`chat_profile_budget_unmet` keeps automatic encoders on CPU when an explicitly locked, measured chat profile leaves insufficient encoder memory. The selected chat artifact remains the one requested.

`gpu_plan_unavailable` means chat planning could not resolve its inputs. Settings preparation retains the unplanned configuration and reports this reason. The planner logs at INFO when the resolved result changes.

`planned_profile_precision_unmeasured` means the selected encoder artifact is not FP16 and has no measured activation bound for the planned profile. It runs on CPU, exposes this placement reason in its session status, and logs once per model path.

The chat hold refusal `EXTERNAL_SERVER` means the self-test would replace an externally owned server. The external server is left running.

A GPU cap that cannot retain the weights or fit one padded item routes the call through the CPU session. CPU work is not rejected by the GPU packing budget.
