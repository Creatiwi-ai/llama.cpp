package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.internal.InferenceEngineImpl.Companion.getInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * JNI wrapper for the llama.cpp library providing Android-friendly access to large language models.
 *
 * This class implements a singleton pattern for managing the lifecycle of a single LLM instance.
 * All operations are executed on a dedicated single-threaded dispatcher to ensure thread safety
 * with the underlying C++ native code.
 *
 * The typical usage flow is:
 * 1. Get instance via [getInstance]
 * 2. Load a model with [loadModel]
 * 3. Send prompts with [sendUserPrompt]
 * 4. Generate responses as token streams
 * 5. Perform [cleanUp] when done with a model
 * 6. Properly [destroy] when completely done
 *
 * State transitions are managed automatically and validated at each operation.
 *
 * @see ai_chat.cpp for the native implementation details
 */
internal class InferenceEngineImpl private constructor(
    private val nativeLibDir: String
) : InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile
        private var instance: InferenceEngine? = null

        /**
         * Create or obtain [InferenceEngineImpl]'s single instance.
         *
         * @param Context for obtaining native library directory
         * @throws IllegalArgumentException if native library path is invalid
         * @throws UnsatisfiedLinkError if library failed to load
         */
        internal fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }

                try {
                    Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                    InferenceEngineImpl(nativeLibDir).also { instance = it }
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                    throw e
                }
            }
    }

    /**
     * JNI methods
     * @see ai_chat.cpp
     *
     * None of these is @FastNative. Model load, prepare, prompt prefill, token decode, benchmark,
     * KV cache reset and unload run for milliseconds to seconds, and while a thread is inside a
     * @FastNative call the runtime cannot suspend it for garbage collection: threads that are
     * already suspended, the main thread included, stay frozen until the call returns. The platform
     * javadoc says "Do not use this annotation for long-running methods". The rest (init,
     * systemInfo, shutdown) run once per process, where the annotation saves nothing.
     */
    private external fun init(nativeLibDir: String)

    private external fun load(modelPath: String, useJinja: Boolean): Int

    private external fun prepare(): Int

    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    private external fun nativeResetContext()

    private external fun unload()

    private external fun shutdown()

    private val _state =
        MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile
    private var _cancelGeneration = false

    /**
     * Whether native [load] succeeded and [unload] has not run since, i.e. whether the native side
     * holds a model. Read and written on [llamaDispatcher] only.
     */
    private var _nativeModelLoaded = false

    /**
     * Single-threaded coroutine dispatcher & scope for LLama asynchronous operations
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    /**
     * Load the LLM
     */
    override suspend fun loadModel(pathToModel: String, useJinja: Boolean) =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.Initialized) {
                "Cannot load model in ${_state.value.javaClass.simpleName}!"
            }

            try {
                Log.i(TAG, "Checking access to model file... \n$pathToModel")
                File(pathToModel).let {
                    require(it.exists()) { "File not found" }
                    require(it.isFile) { "Not a valid file" }
                    require(it.canRead()) { "Cannot read file" }
                }

                Log.i(TAG, "Loading model... \n$pathToModel")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.LoadingModel
                load(pathToModel, useJinja).let {
                    // TODO-han.yin: find a better way to pass other error codes
                    if (it != 0) throw UnsupportedArchitectureException()
                }
                _nativeModelLoaded = true
                prepare().let {
                    if (it != 0) throw IOException("Failed to prepare resources")
                }
                Log.i(TAG, "Model loaded!")
                _readyForSystemPrompt = true

                _cancelGeneration = false
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: Exception) {
                Log.e(TAG, (e.message ?: "Error loading model") + "\n" + pathToModel, e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }

    /**
     * Process the plain text system prompt
     *
     * TODO-han.yin: return error code if system prompt not correct processed?
     */
    override suspend fun setSystemPrompt(prompt: String) =
        withContext(llamaDispatcher) {
            require(prompt.isNotBlank()) { "Cannot process empty system prompt!" }
            check(_readyForSystemPrompt) { "System prompt must be set ** RIGHT AFTER ** model loaded!" }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
            }

            Log.i(TAG, "Sending system prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            processSystemPrompt(prompt).let { result ->
                if (result != 0) {
                    RuntimeException("Failed to process system prompt: $result").also {
                        _state.value = InferenceEngine.State.Error(it)
                        throw it
                    }
                }
            }
            Log.i(TAG, "System prompt processed! Awaiting user prompt...")
            _state.value = InferenceEngine.State.ModelReady
        }

    /**
     * Send plain text user prompt to LLM, which starts generating tokens in a [Flow]
     */
    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = flow {
        require(message.isNotEmpty()) { "User prompt discarded due to being empty!" }
        check(_state.value is InferenceEngine.State.ModelReady) {
            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
        }

        try {
            Log.i(TAG, "Sending user prompt...")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingUserPrompt

            // Keep this a plain statement. As `processUserPrompt(...).let { if (it != 0) return@flow }`
            // Kotlin 2.0.0 typed the `let` as Nothing and compiled everything below it into
            // `throw KotlinNothingValueException()`, so 1.1.0 to 1.4.1 never produced a token.
            // publish-aar.yml checks the bytecode for this (examples/llama.android/scripts/verify-aar-bytecode.sh).
            val prefillResult = processUserPrompt(message, predictLength)
            if (prefillResult != 0) {
                Log.e(TAG, "Failed to process user prompt: $prefillResult")
                // The model and context are still loaded, but the KV cache and chat history may
                // hold part of this prompt: call resetContext() before the next turn.
                _state.value = InferenceEngine.State.ModelReady
                return@flow
            }

            Log.i(TAG, "User prompt processed. Generating assistant prompt...")
            _state.value = InferenceEngine.State.Generating
            while (!_cancelGeneration) {
                generateNextToken()?.let { utf8token ->
                    if (utf8token.isNotEmpty()) emit(utf8token)
                } ?: break
            }
            if (_cancelGeneration) {
                Log.i(TAG, "Assistant generation aborted per requested.")
            } else {
                Log.i(TAG, "Assistant generation complete. Awaiting user prompt...")
            }
            _state.value = InferenceEngine.State.ModelReady
        } catch (e: CancellationException) {
            Log.i(TAG, "Assistant generation's flow collection cancelled.")
            _state.value = InferenceEngine.State.ModelReady
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation!", e)
            _state.value = InferenceEngine.State.Error(e)
            throw e
        }
    }.flowOn(llamaDispatcher)

    /**
     * Benchmark the model
     */
    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Benchmark request discarded due to: $state"
            }
            Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
            _readyForSystemPrompt = false   // Just to be safe
            _state.value = InferenceEngine.State.Benchmarking
            benchModel(pp, tg, pl, nr).also {
                _state.value = InferenceEngine.State.ModelReady
            }
        }

    /**
     * Reset KV cache, chat history, and sampler state without unloading the
     * model. Cheap alternative to [cleanUp] + [loadModel] for single-turn
     * usage. Leaves the engine in [InferenceEngine.State.ModelReady] and
     * re-arms `_readyForSystemPrompt` so the next [setSystemPrompt] call is
     * honored by the engine.
     *
     * Must be called only when the engine is in [InferenceEngine.State.ModelReady].
     * No-op otherwise, with a warning log.
     */
    override fun resetContext() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            val currentState = _state.value
            if (currentState !is InferenceEngine.State.ModelReady) {
                Log.w(TAG, "resetContext: engine not in ModelReady (is ${currentState.javaClass.simpleName}), ignoring")
                // Still clear cancel flag so the engine is not wedged.
                _cancelGeneration = false
                return@runBlocking
            }
            try {
                Log.i(TAG, "resetContext: clearing KV cache and chat state")
                nativeResetContext()
                // Re-arm system prompt gate so the next setSystemPrompt is honored.
                _readyForSystemPrompt = true
                Log.i(TAG, "resetContext: done")
            } catch (e: Exception) {
                Log.e(TAG, "resetContext: native call failed", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            } finally {
                // Always clear the cancel flag, even if the native call threw,
                // so subsequent generations are not permanently blocked.
                _cancelGeneration = false
            }
        }
    }

    /**
     * Unloads the model and frees resources, or reset error states
     */
    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources...")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()
                    _nativeModelLoaded = false

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }

                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states...")
                    // An error after a successful load (prepare, system prompt, generation) leaves
                    // the model in native memory. Free it here: the next loadModel would otherwise
                    // keep it allocated next to the new one.
                    if (_nativeModelLoaded) {
                        Log.i(TAG, "Unloading the model left by the error...")
                        unload()
                        _nativeModelLoaded = false
                    }
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }

                else -> throw IllegalStateException("Cannot unload model in ${state.javaClass.simpleName}")
            }
        }
    }

    /**
     * Cancel all ongoing coroutines and free GGML backends
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when(_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> { unload(); shutdown() }
            }
        }
        llamaScope.cancel()
    }
}
