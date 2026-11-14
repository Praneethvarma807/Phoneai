package com.example.ai

import com.example.domain.model.DeviceTier
import com.example.domain.model.LocalModelSpec

object ModelCatalog {
    val publicModels: List<LocalModelSpec> = listOf(
        LocalModelSpec(
            id = "qwen3-0.6b-litert",
            displayName = "Qwen3 0.6B Instruct",
            family = "Qwen3 (Open-Weight)",
            fileName = "Qwen3-0.6B.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
            expectedSizeBytes = 614_000_000L,
            minRamMb = 3072L,
            minStorageMb = 800L,
            license = "Apache 2.0",
            description = "Compact 0.6B open-weight model from litert-community for CPU/GPU mobile tool planning without API keys.",
            recommendedTier = DeviceTier.MEDIUM_RAM
        ),
        LocalModelSpec(
            id = "gemma3-1b-it-litert",
            displayName = "Gemma 3 1B IT (Q4)",
            family = "Gemma Open-Weight",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.litertlm",
            expectedSizeBytes = 555_000_000L,
            minRamMb = 3072L,
            minStorageMb = 750L,
            license = "Gemma Terms of Use",
            description = "Fast 4-bit quantized 1B instruction-tuned model for offline natural-language intent & JSON plan generation.",
            recommendedTier = DeviceTier.MEDIUM_RAM
        ),
        LocalModelSpec(
            id = "gemma4-e2b-it-litert",
            displayName = "Gemma 4 E2B IT",
            family = "Gemma Open-Weight",
            fileName = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_600_000_000L,
            minRamMb = 6144L,
            minStorageMb = 3000L,
            license = "Gemma Terms of Use",
            description = "Higher-reasoning open-weight model for flagship devices (6+ GB RAM) supporting CPU and GPU backends.",
            recommendedTier = DeviceTier.HIGH_RAM
        )
    )
}
