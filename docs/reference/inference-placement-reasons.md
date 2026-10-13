---
title: Inference placement reasons
type: reference
status: stable
description: Additive placement and refusal reasons for GPU planning and chat ownership.
---

# Inference placement reasons

`encoder_device_conflict` means an encoder was explicitly placed on a different CUDA device from the device-memory plan. That role keeps its explicit device and unplanned policy; chat and the other roles keep the shared plan. It is an additive `user_setting_unmet` cause, distinct from insufficient encoder memory.

`chat_profile_budget_unmet` keeps automatic encoders on CPU when an explicitly locked, measured chat profile leaves insufficient encoder memory. The selected chat artifact remains the one requested.

`gpu_plan_unavailable` means chat planning could not resolve its inputs. Settings preparation retains the unplanned configuration and reports this reason. The planner logs at INFO when the resolved result changes.

`planned_profile_precision_unmeasured` means the selected encoder artifact is not FP16 and has no measured activation bound for the planned profile. It runs on CPU, exposes this placement reason in its session status, and logs once per model path.

The chat hold refusal `EXTERNAL_SERVER` means the self-test would replace an externally owned server. The external server is left running.

A GPU cap that cannot retain the weights or fit one padded item routes the call through the CPU session. CPU work is not rejected by the GPU packing budget.
