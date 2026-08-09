package com.example.osmemory.core.model

/** 阶段四端侧演示模型：Qwen 0.5B（约 6.3 亿参数），固定版本与校验值保证可复现。 */
object LocalModelSpec {
    const val ID = "Qwen/Qwen2.5-0.5B-Instruct-GGUF:Q4_K_M"
    const val DISPLAY_NAME = "Qwen2.5-0.5B-Instruct Q4_K_M"
    const val FILE_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val EXPECTED_SIZE = 491_400_032L
    const val SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    const val REVISION = "9217f5db79a29953eb74d5343926648285ec7e67"
    const val DOWNLOAD_URL =
        "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/$REVISION/$FILE_NAME?download=true"
}
