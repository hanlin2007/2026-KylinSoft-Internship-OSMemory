cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(NOT ANDROID_ABI STREQUAL "x86_64")
    message(FATAL_ERROR "Phase 4 emulator component only builds x86_64")
endif()

set(GGML_SYSTEM_ARCH "x86")
set(GGML_CPU_KLEIDIAI OFF)
set(GGML_OPENMP OFF)

set(LLAMA_SRC "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
add_subdirectory("${LLAMA_SRC}" build-llama)

# Keep the official binding logic, but replace its API-30-only logging helper.
file(READ
    "${LLAMA_SRC}/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
    AI_CHAT_SOURCE)
string(REPLACE
    "#include \"logging.h\""
    "#include \"logging_compat.h\""
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}")
string(REPLACE
    "static llama_model                      * g_model;\nstatic llama_context                    * g_context;\nstatic llama_batch                        g_batch;\nstatic common_chat_templates_ptr          g_chat_templates;\nstatic common_sampler                   * g_sampler;"
    "static llama_model                      * g_model = nullptr;\nstatic llama_context                    * g_context = nullptr;\nstatic llama_batch                        g_batch{};\nstatic bool                               g_batch_initialized = false;\nstatic common_chat_templates_ptr          g_chat_templates;\nstatic common_sampler                   * g_sampler = nullptr;"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}")
string(REPLACE
    "g_batch = llama_batch_init(BATCH_SIZE, 0, 1);\n    g_chat_templates"
    "g_batch = llama_batch_init(BATCH_SIZE, 0, 1);\n    g_batch_initialized = true;\n    g_chat_templates"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}")
string(REPLACE
    "    // Reset long-term & short-term states\n    reset_long_term_states();\n    reset_short_term_states();\n\n    // Free up resources\n    common_sampler_free(g_sampler);\n    g_chat_templates.reset();\n    llama_batch_free(g_batch);\n    llama_free(g_context);\n    llama_model_free(g_model);"
    "    // Also support partial cleanup after load/prepare/prompt failures.\n    reset_long_term_states(g_context != nullptr);\n    reset_short_term_states();\n\n    if (g_sampler != nullptr) {\n        common_sampler_free(g_sampler);\n        g_sampler = nullptr;\n    }\n    g_chat_templates.reset();\n    if (g_batch_initialized) {\n        llama_batch_free(g_batch);\n        g_batch = {};\n        g_batch_initialized = false;\n    }\n    if (g_context != nullptr) {\n        llama_free(g_context);\n        g_context = nullptr;\n    }\n    if (g_model != nullptr) {\n        llama_model_free(g_model);\n        g_model = nullptr;\n    }"
    AI_CHAT_SOURCE
    "${AI_CHAT_SOURCE}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/ai_chat_compat.cpp" "${AI_CHAT_SOURCE}")

add_library(${CMAKE_PROJECT_NAME} SHARED
    "${CMAKE_CURRENT_BINARY_DIR}/ai_chat_compat.cpp")

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
    GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
    GGML_CPU_KLEIDIAI=0
    GGML_OPENMP=0)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}"
    "${LLAMA_SRC}"
    "${LLAMA_SRC}/common"
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
    "${LLAMA_SRC}/ggml/src")

target_link_libraries(${CMAKE_PROJECT_NAME}
    llama
    llama-common
    android
    log)
