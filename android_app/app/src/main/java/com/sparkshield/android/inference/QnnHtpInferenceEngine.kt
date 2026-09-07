package com.sparkshield.android.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Hardware-accelerated inference engine executing on Qualcomm Hexagon Tensor Processor (HTP)
 * via Qualcomm AI Engine Direct (QNN) SDK native bridge.
 *
 * Implements transparent automatic fallback to [CpuOnnxInferenceEngine] when running
 * on non-Snapdragon platforms, emulators, or if the QNN HTP runtime is unavailable.
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
                Log.w(TAG, "Native QNN JNI library not loaded: ${t.message}. CPU fallback will be used.")
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

        if (forceCpuFallback || !isNativeLibraryLoaded) {
            Log.i(TAG, "Defaulting directly to CPU ONNX inference engine (forceFallback=$forceCpuFallback, libLoaded=$isNativeLibraryLoaded)")
            isFallbackActive = true
            val fallbackResult = cpuFallbackEngine.load()
            isLoaded = fallbackResult.isSuccess
            return@withContext fallbackResult
        }

        try {
            if (context == null) {
                Log.w(TAG, "Context is null; activating CPU fallback engine.")
                isFallbackActive = true
                val fallbackResult = cpuFallbackEngine.load()
                isLoaded = fallbackResult.isSuccess
                return@withContext fallbackResult
            }

            // 1. Check if Hexagon HTP hardware is present
            val htpSupported = try {
                nativeIsHtpSupported()
            } catch (e: Throwable) {
                Log.w(TAG, "nativeIsHtpSupported probe failed: ${e.message}")
                false
            }

            if (!htpSupported) {
                Log.w(TAG, "Qualcomm Hexagon HTP not available on this device/kernel. Falling back to CPU ONNX runtime.")
                isFallbackActive = true
                val fallbackResult = cpuFallbackEngine.load()
                isLoaded = fallbackResult.isSuccess
                return@withContext fallbackResult
            }

            // 2. Load context binary from assets
            val binaryBytes: ByteArray = try {
                context.assets.open(htpAssetPath).use { it.readBytes() }
            } catch (fnf: Exception) {
                Log.w(TAG, "QNN HTP context binary '$htpAssetPath' not found in assets: ${fnf.message}. Activating CPU fallback.")
                isFallbackActive = true
                val fallbackResult = cpuFallbackEngine.load()
                isLoaded = fallbackResult.isSuccess
                return@withContext fallbackResult
            }

            // 3. Allocate direct buffer for context binary
            val contextDirectBuffer = ByteBuffer.allocateDirect(binaryBytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(binaryBytes)
                rewind()
            }

            // 4. Initialize native QNN HTP context
            nativeHandle = nativeInit(contextDirectBuffer, binaryBytes.size)
            if (nativeHandle == 0L) {
                Log.w(TAG, "Native QNN HTP initialization failed. Activating CPU fallback.")
                isFallbackActive = true
                val fallbackResult = cpuFallbackEngine.load()
                isLoaded = fallbackResult.isSuccess
                return@withContext fallbackResult
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
            Log.i(TAG, "Qualcomm QNN Hexagon HTP inference engine initialized successfully.")
            return@withContext Result.success(Unit)

        } catch (t: Throwable) {
            Log.w(TAG, "Unexpected error loading QNN HTP engine: ${t.message}. Falling back to CPU ONNX engine.", t)
            isFallbackActive = true
            val fallbackResult = cpuFallbackEngine.load()
            isLoaded = fallbackResult.isSuccess
            return@withContext fallbackResult
        }
    }

    override suspend fun infer(input: FloatArray): Result<InferenceResult> = withContext(Dispatchers.Default) {
        if (!isLoaded) {
            return@withContext Result.failure(IllegalStateException("Inference engine not loaded. Call load() first."))
        }

        if (isFallbackActive) {
            return@withContext cpuFallbackEngine.infer(input)
        }

        if (input.size != INPUT_ELEMENT_COUNT) {
            return@withContext Result.failure(
                IllegalArgumentException("Expected $INPUT_ELEMENT_COUNT input elements, got ${input.size}")
            )
        }

        try {
            val inFloatBuf = inputFloatBuffer!!
            val outFloatBuf = outputFloatBuffer!!
            val inDirectBuf = inputDirectBuffer!!
            val outDirectBuf = outputDirectBuffer!!

            // Copy floats to direct buffer
            inFloatBuf.rewind()
            inFloatBuf.put(input)
            inDirectBuf.rewind()
            outDirectBuf.rewind()

            // Execute on Hexagon HTP
            val latencyUs = nativeInfer(nativeHandle, inDirectBuf, outDirectBuf)
            if (latencyUs < 0) {
                Log.w(TAG, "Native HTP inference failed; switching dynamically to CPU fallback.")
                isFallbackActive = true
                cpuFallbackEngine.load()
                return@withContext cpuFallbackEngine.infer(input)
            }

            outFloatBuf.rewind()
            val logits = FloatArray(OUTPUT_ELEMENT_COUNT)
            outFloatBuf.get(logits)

            // Softmax transformation
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
            for (i in probabilities.indices) {
                probabilities[i] = exps[i] / max(sumExp, 1e-9f)
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    bestClassIndex = i
                }
            }

            val label = ClassLabels.fromId(bestClassIndex).name
            val result = InferenceResult(
                label = label,
                classIndex = bestClassIndex,
                probabilities = probabilities,
                confidence = maxProb,
                inferenceTimeUs = latencyUs
            )
            return@withContext Result.success(result)

        } catch (t: Throwable) {
            Log.e(TAG, "Error executing HTP inference: ${t.message}", t)
            isFallbackActive = true
            cpuFallbackEngine.load()
            return@withContext cpuFallbackEngine.infer(input)
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            try {
                nativeClose(nativeHandle)
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing native QNN handle: ${t.message}")
            }
            nativeHandle = 0L
        }

        inputDirectBuffer = null
        outputDirectBuffer = null
        inputFloatBuffer = null
        outputFloatBuffer = null
        isLoaded = false

        cpuFallbackEngine.close()
        Log.i(TAG, "QnnHtpInferenceEngine closed cleanly.")
    }
}
