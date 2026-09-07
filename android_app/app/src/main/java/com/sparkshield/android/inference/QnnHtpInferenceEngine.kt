package com.sparkshield.android.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/**
 * Qualcomm Hexagon Tensor Processor (HTP) NPU Hardware-Accelerated Inference Engine.
 *
 * Interacts with the Qualcomm AI Engine Direct (QNN / QAIRT) SDK native C++ runtime.
 * Implements robust, transparent fallback to [CpuOnnxInferenceEngine] whenever:
 *   - Native JNI library cannot be loaded
 *   - Device lacks Qualcomm Snapdragon FastRPC / HTP drivers
 *   - Context binary asset is absent, invalid, or uncompiled
 *   - Native QNN graph initialization fails
 *   - Runtime HTP execution encounters a failure
 */
class QnnHtpInferenceEngine(
    private val context: Context? = null,
    private val htpAssetPath: String = DEFAULT_HTP_ASSET,
    private val cpuFallbackEngine: InferenceEngine = CpuOnnxInferenceEngine(context),
    private val forceCpuFallback: Boolean = false
) : InferenceEngine {

    companion object {
        private const val TAG = "QnnHtpInferenceEngine"
        const val DEFAULT_HTP_ASSET = "sparkshield_htp.bin"
        const val INPUT_ELEMENT_COUNT = 128
        const val OUTPUT_ELEMENT_COUNT = 4

        private var isNativeLibraryLoaded = false

        init {
            try {
                System.loadLibrary("sparkshield_qnn_jni")
                isNativeLibraryLoaded = true
                Log.i(TAG, "libsparkshield_qnn_jni.so loaded successfully.")
            } catch (t: Throwable) {
                isNativeLibraryLoaded = false
                Log.w(TAG, "Native QNN JNI library unavailable: ${t.message}. CPU fallback will be used.")
            }
        }

        @JvmStatic
        private external fun nativeIsHtpSupported(): Boolean

        @JvmStatic
        private external fun nativeInit(contextBuffer: ByteBuffer, bufferSize: Int): Long

        @JvmStatic
        private external fun nativeInfer(handle: Long, inputBuf: ByteBuffer, outputBuf: ByteBuffer): Long

        @JvmStatic
        private external fun nativeClose(handle: Long)
    }

    private var nativeHandle: Long = 0L
    private var isFallbackActive: Boolean = forceCpuFallback
    private var isLoaded: Boolean = false

    // Diagnostic & Telemetry Fields
    var qnnInitStatus: String = "UNINITIALIZED"
        private set

    var fallbackReason: String? = null
        private set

    var modelContextHash: String? = null
        private set

    var lastInferenceLatencyUs: Long = 0L
        private set

    private val _htpExecutionCount = AtomicLong(0L)
    val htpExecutionCount: Long get() = _htpExecutionCount.get()

    private val _fallbackExecutionCount = AtomicLong(0L)
    val fallbackExecutionCount: Long get() = _fallbackExecutionCount.get()

    private val _errorCount = AtomicLong(0L)
    val errorCount: Long get() = _errorCount.get()

    // Direct ByteBuffers for zero-copy JNI memory access
    private var inputDirectBuffer: ByteBuffer? = null
    private var outputDirectBuffer: ByteBuffer? = null
    private var inputFloatBuffer: FloatBuffer? = null
    private var outputFloatBuffer: FloatBuffer? = null

    val activeAccelerator: String
        get() = if (isFallbackActive) "CPU (ONNX)" else "Hexagon HTP (QNN)"

    val isUsingHtp: Boolean
        get() = !isFallbackActive && isLoaded && nativeHandle != 0L

    override suspend fun load(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext Result.success(Unit)

        if (forceCpuFallback) {
            return@withContext activateCpuFallback("CPU fallback explicitly requested via configuration.")
        }

        if (!isNativeLibraryLoaded) {
            return@withContext activateCpuFallback("libsparkshield_qnn_jni.so could not be loaded by dynamic linker.")
        }

        if (context == null) {
            return@withContext activateCpuFallback("Android Context is null; cannot access assets.")
        }

        try {
            // 1. Probe Qualcomm FastRPC & Hexagon HTP hardware availability
            val htpSupported = try {
                nativeIsHtpSupported()
            } catch (e: Throwable) {
                Log.w(TAG, "nativeIsHtpSupported probe error: ${e.message}")
                false
            }

            if (!htpSupported) {
                return@withContext activateCpuFallback(
                    "Qualcomm FastRPC drivers or Hexagon HTP NPU hardware unavailable on this platform."
                )
            }

            // 2. Load context binary asset
            val binaryBytes: ByteArray = try {
                context.assets.open(htpAssetPath).use { it.readBytes() }
            } catch (fnf: FileNotFoundException) {
                return@withContext activateCpuFallback(
                    "QNN HTP context binary '$htpAssetPath' not packaged in APK assets. Build context binary using official QAIRT SDK."
                )
            } catch (e: Exception) {
                return@withContext activateCpuFallback("Failed to read context binary '$htpAssetPath': ${e.message}")
            }

            // Compute SHA-256 hash of context binary for audit trail
            modelContextHash = computeSha256(binaryBytes)
            Log.i(TAG, "Context binary loaded: $htpAssetPath (${binaryBytes.size} bytes, SHA256=$modelContextHash)")

            // 3. Allocate Direct ByteBuffer for context binary
            val contextDirectBuffer = ByteBuffer.allocateDirect(binaryBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(binaryBytes)
                rewind()
            }

            // 4. Initialize native QNN HTP context & graph
            nativeHandle = nativeInit(contextDirectBuffer, binaryBytes.size)
            if (nativeHandle == 0L) {
                return@withContext activateCpuFallback(
                    "Native QNN initialization failed: libQnnHtp.so missing or context binary incompatible with SoC architecture."
                )
            }

            // 5. Allocate Direct ByteBuffers for input [1, 1, 128] and output [1, 4]
            inputDirectBuffer = ByteBuffer.allocateDirect(INPUT_ELEMENT_COUNT * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            inputFloatBuffer = inputDirectBuffer!!.asFloatBuffer()

            outputDirectBuffer = ByteBuffer.allocateDirect(OUTPUT_ELEMENT_COUNT * 4).apply {
                order(ByteOrder.nativeOrder())
            }
            outputFloatBuffer = outputDirectBuffer!!.asFloatBuffer()

            isFallbackActive = false
            isLoaded = true
            qnnInitStatus = "READY_HTP"
            fallbackReason = null
            Log.i(TAG, "Qualcomm Hexagon HTP NPU inference engine initialized successfully.")
            return@withContext Result.success(Unit)

        } catch (t: Throwable) {
            _errorCount.incrementAndGet()
            Log.e(TAG, "Unexpected error loading QNN HTP engine: ${t.message}", t)
            return@withContext activateCpuFallback("Unexpected initialization exception: ${t.message}")
        }
    }

    private suspend fun activateCpuFallback(reason: String): Result<Unit> {
        Log.w(TAG, "Activating CPU Fallback. Reason: $reason")
        isFallbackActive = true
        qnnInitStatus = "FALLBACK_CPU"
        fallbackReason = reason
        val result = cpuFallbackEngine.load()
        isLoaded = result.isSuccess
        return result
    }

    override suspend fun infer(input: FloatArray): Result<InferenceResult> = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            _errorCount.incrementAndGet()
            return@withContext Result.failure(IllegalStateException("Inference engine not loaded. Call load() first."))
        }

        if (input.size != INPUT_ELEMENT_COUNT) {
            _errorCount.incrementAndGet()
            return@withContext Result.failure(
                IllegalArgumentException("Expected $INPUT_ELEMENT_COUNT input elements, got ${input.size}")
            )
        }

        if (isFallbackActive) {
            _fallbackExecutionCount.incrementAndGet()
            return@withContext cpuFallbackEngine.infer(input)
        }

        try {
            val inFloatBuf = inputFloatBuffer ?: return@withContext fallbackOnInferenceError("Input buffer null", input)
            val outFloatBuf = outputFloatBuffer ?: return@withContext fallbackOnInferenceError("Output buffer null", input)
            val inDirectBuf = inputDirectBuffer ?: return@withContext fallbackOnInferenceError("Direct in-buffer null", input)
            val outDirectBuf = outputDirectBuffer ?: return@withContext fallbackOnInferenceError("Direct out-buffer null", input)

            // Direct buffer write (zero Java heap array copy in JNI)
            inFloatBuf.rewind()
            inFloatBuf.put(input)
            inDirectBuf.rewind()
            outDirectBuf.rewind()

            // Execute inference on Qualcomm Hexagon HTP
            val latencyUs = nativeInfer(nativeHandle, inDirectBuf, outDirectBuf)
            if (latencyUs < 0) {
                return@withContext fallbackOnInferenceError("Native HTP graph execution failed (code: $latencyUs)", input)
            }

            lastInferenceLatencyUs = latencyUs
            _htpExecutionCount.incrementAndGet()

            outFloatBuf.rewind()
            val logits = FloatArray(OUTPUT_ELEMENT_COUNT)
            outFloatBuf.get(logits)

            // Stable Softmax calculation
            var maxLogit = Float.NEGATIVE_INFINITY
            for (l in logits) {
                if (l > maxLogit) maxLogit = l
            }

            val exps = FloatArray(OUTPUT_ELEMENT_COUNT)
            var sumExp = 0.0f
            for (i in logits.indices) {
                exps[i] = exp(logits[i] - maxLogit)
                sumExp += exps[i]
            }

            val probabilities = FloatArray(OUTPUT_ELEMENT_COUNT)
            var maxProb = -1.0f
            var bestClassIndex = 0
            for (i in logits.indices) {
                probabilities[i] = if (sumExp > 0f) exps[i] / sumExp else 0.25f
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    bestClassIndex = i
                }
            }

            val classification = when (bestClassIndex) {
                0 -> ClassLabels.NORMAL
                1 -> ClassLabels.EMP
                2 -> ClassLabels.OPTICAL
                3 -> ClassLabels.SURGE
                else -> ClassLabels.NORMAL
            }

            return@withContext Result.success(
                InferenceResult(
                    classification = classification,
                    confidence = maxProb,
                    classIndex = bestClassIndex,
                    probabilities = probabilities,
                    inferenceTimeUs = latencyUs
                )
            )

        } catch (t: Throwable) {
            _errorCount.incrementAndGet()
            return@withContext fallbackOnInferenceError("Inference exception: ${t.message}", input)
        }
    }

    private suspend fun fallbackOnInferenceError(reason: String, input: FloatArray): Result<InferenceResult> {
        Log.w(TAG, "Inference error on HTP; switching dynamically to CPU fallback. Reason: $reason")
        isFallbackActive = true
        qnnInitStatus = "FALLBACK_CPU"
        fallbackReason = reason
        _fallbackExecutionCount.incrementAndGet()
        cpuFallbackEngine.load()
        return cpuFallbackEngine.infer(input)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            try {
                nativeClose(nativeHandle)
                Log.i(TAG, "Native QNN HTP context handle released.")
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing native QNN context: ${t.message}")
            }
            nativeHandle = 0L
        }

        inputDirectBuffer = null
        outputDirectBuffer = null
        inputFloatBuffer = null
        outputFloatBuffer = null
        isLoaded = false
        qnnInitStatus = "CLOSED"

        try {
            cpuFallbackEngine.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Error closing CPU fallback engine: ${t.message}")
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
