#include <jni.h>
#include <string>
#include <memory>
#include "QnnApi.h"

extern "C" {

/**
 * Probes whether the device has a Qualcomm Hexagon Tensor Processor (HTP) available.
 */
JNIEXPORT jboolean JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeIsHtpSupported(
        JNIEnv* env,
        jclass /* clazz */) {
    QnnHtpBackend probe;
    return static_cast<jboolean>(probe.isHtpSupported());
}

/**
 * Initializes the QNN HTP context from the binary buffer loaded from assets.
 * Returns a pointer handle to the allocated QnnHtpBackend instance, or 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeInit(
        JNIEnv* env,
        jobject /* thiz */,
        jobject contextBuffer,
        jint bufferSize) {
    if (contextBuffer == nullptr || bufferSize <= 0) {
        LOGE("Invalid context buffer passed to nativeInit");
        return 0;
    }

    auto* bufferPtr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(contextBuffer));
    if (bufferPtr == nullptr) {
        LOGE("Failed to get direct buffer address for context binary");
        return 0;
    }

    auto backend = std::make_unique<QnnHtpBackend>();
    if (!backend->initializeFromBinary(bufferPtr, static_cast<size_t>(bufferSize))) {
        LOGE("Failed to initialize QNN HTP context from binary buffer");
        return 0;
    }

    LOGI("QNN HTP execution context initialized successfully.");
    return reinterpret_cast<jlong>(backend.release());
}

/**
 * Executes tensor inference on the Hexagon HTP backend using Direct ByteBuffers.
 * Returns the execution latency in microseconds, or -1 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeInfer(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jobject inputBuf,
        jobject outputBuf) {
    if (handle == 0) {
        LOGE("Null backend handle passed to nativeInfer");
        return -1;
    }

    auto* backend = reinterpret_cast<QnnHtpBackend*>(handle);

    auto* inPtr = static_cast<const float*>(env->GetDirectBufferAddress(inputBuf));
    auto* outPtr = static_cast<float*>(env->GetDirectBufferAddress(outputBuf));

    if (inPtr == nullptr || outPtr == nullptr) {
        LOGE("Failed to retrieve direct buffer addresses for input/output");
        return -1;
    }

    int64_t latencyUs = backend->execute(inPtr, outPtr, 128, 4);
    return static_cast<jlong>(latencyUs);
}

/**
 * Releases QNN context and backend resources.
 */
JNIEXPORT void JNICALL
Java_com_sparkshield_android_inference_QnnHtpInferenceEngine_nativeClose(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle) {
    if (handle != 0) {
        auto* backend = reinterpret_cast<QnnHtpBackend*>(handle);
        delete backend;
        LOGI("QNN HTP backend released successfully.");
    }
}

} // extern "C"
