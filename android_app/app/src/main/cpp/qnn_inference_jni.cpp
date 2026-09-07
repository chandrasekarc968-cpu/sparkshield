#include <jni.h>
#include <string>
#include <memory>
#include "QnnApi.h"

extern "C" {

/**
 * Probes whether the device has a Qualcomm Hexagon Tensor Processor (HTP) available
 * via FastRPC driver nodes (/dev/fastrpc-cdsp, /dev/adsprpc-smd) and libQnnHtp.so.
 */
JNIEXPORT jboolean JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeIsHtpSupported(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    QnnHtpBackend probe;
    return static_cast<jboolean>(probe.isHtpSupported());
}

/**
 * Initializes the QNN HTP context from the serialized binary buffer.
 * Returns a pointer handle to the allocated QnnHtpBackend instance, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeInit(
        JNIEnv* env,
        jobject /* thiz */,
        jobject contextBuffer,
        jint bufferSize) {
    if (contextBuffer == nullptr || bufferSize <= 0) {
        LOGE("nativeInit: Invalid contextBuffer or bufferSize=%d", bufferSize);
        return 0;
    }

    auto* bufferPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(contextBuffer));
    if (bufferPtr == nullptr) {
        LOGE("nativeInit: GetDirectBufferAddress returned null");
        return 0;
    }

    jlong bufferCapacity = env->GetDirectBufferCapacity(contextBuffer);
    if (bufferCapacity < bufferSize) {
        LOGE("nativeInit: Buffer capacity (%lld) < bufferSize (%d)", static_cast<long long>(bufferCapacity), bufferSize);
        return 0;
    }

    auto backend = std::make_unique<QnnHtpBackend>();
    if (!backend->initializeFromBinary(bufferPtr, static_cast<size_t>(bufferSize))) {
        LOGW("nativeInit: Failed to materialize genuine QNN graph on Hexagon HTP. Clean fallback will occur.");
        return 0;
    }

    LOGI("nativeInit: Qualcomm Hexagon HTP execution backend initialized successfully.");
    return reinterpret_cast<jlong>(backend.release());
}

/**
 * Executes tensor inference on the Hexagon HTP backend using Direct ByteBuffers.
 * Strictly validates buffer sizes (128 floats input, 4 floats output).
 * Returns execution latency in microseconds, or negative error code on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeInfer(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jobject inputBuf,
        jobject outputBuf) {
    if (handle == 0) {
        LOGE("nativeInfer: Null backend handle passed");
        return -1;
    }

    if (inputBuf == nullptr || outputBuf == nullptr) {
        LOGE("nativeInfer: Null input/output buffer");
        return -2;
    }

    jlong inCapacity = env->GetDirectBufferCapacity(inputBuf);
    jlong outCapacity = env->GetDirectBufferCapacity(outputBuf);

    if (inCapacity < static_cast<jlong>(128 * sizeof(float)) ||
        outCapacity < static_cast<jlong>(4 * sizeof(float))) {
        LOGE("nativeInfer: Insufficient direct buffer capacity: in=%lld, out=%lld",
             static_cast<long long>(inCapacity), static_cast<long long>(outCapacity));
        return -3;
    }

    auto* inPtr = static_cast<const float*>(env->GetDirectBufferAddress(inputBuf));
    auto* outPtr = static_cast<float*>(env->GetDirectBufferAddress(outputBuf));

    if (inPtr == nullptr || outPtr == nullptr) {
        LOGE("nativeInfer: Failed to get DirectBuffer address");
        return -4;
    }

    auto* backend = reinterpret_cast<QnnHtpBackend*>(handle);
    int64_t latencyUs = backend->execute(inPtr, outPtr, 128, 4);
    return static_cast<jlong>(latencyUs);
}

/**
 * Releases QNN context, graph, and backend resources safely.
 */
JNIEXPORT void JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeClose(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    if (handle != 0) {
        auto* backend = reinterpret_cast<QnnHtpBackend*>(handle);
        delete backend;
        LOGI("nativeClose: QNN HTP backend released successfully.");
    }
}

} // extern "C"
