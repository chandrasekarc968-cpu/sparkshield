#ifndef SPARKSHIELD_QNN_API_H
#define SPARKSHIELD_QNN_API_H

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <vector>
#include <chrono>
#include <mutex>
#include <memory>
#include <android/log.h>
#include <dlfcn.h>

#define LOG_TAG "SparkShieldQNN"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Official QNN Magic & Return Codes
#define QNN_BINARY_MAGIC 0x514E4E42 // "QNNB"
#define QNN_SUCCESS 0
#define QNN_ERROR_GENERAL 1
#define QNN_ERROR_NOT_SUPPORTED 2
#define QNN_ERROR_INVALID_ARGUMENT 3
#define QNN_ERROR_MEM_ALLOC 4

// Standard QNN Data Types
typedef enum {
    QNN_DATATYPE_FLOAT_32         = 0x0032,
    QNN_DATATYPE_FLOAT_16         = 0x0016,
    QNN_DATATYPE_INT_8            = 0x0108,
    QNN_DATATYPE_INT_16           = 0x0116,
    QNN_DATATYPE_INT_32           = 0x0132,
    QNN_DATATYPE_UINT_8           = 0x0208,
    QNN_DATATYPE_UINT_16          = 0x0216,
    QNN_DATATYPE_UINT_32          = 0x0232,
    QNN_DATATYPE_UFIXED_POINT_8   = 0x0308,
    QNN_DATATYPE_UFIXED_POINT_16  = 0x0316,
    QNN_DATATYPE_UFIXED_POINT_32  = 0x0332,
    QNN_DATATYPE_UNDEFINED        = 0x7FFFFFFF
} Qnn_DataType_t;

// Opaque QNN Handles
typedef void* Qnn_BackendHandle_t;
typedef void* Qnn_ContextHandle_t;
typedef void* Qnn_GraphHandle_t;
typedef void* Qnn_ProfileHandle_t;
typedef void* Qnn_SignalHandle_t;
typedef void* Qnn_DeviceHandle_t;
typedef void* Qnn_LogHandle_t;
typedef void* Qnn_SystemContextHandle_t;
typedef uint32_t Qnn_ErrorHandle_t;

// Tensor quantization scale/offset
typedef struct {
    float scale;
    int32_t offset;
} Qnn_ScaleOffset_t;

// QNN Tensor Client Buffer
typedef struct {
    void* data;
    uint32_t dataSize;
} Qnn_ClientBuffer_t;

// QNN Tensor Representation (aligned with Qnn_Tensor_t)
typedef struct {
    uint32_t id;
    const char* name;
    Qnn_DataType_t dataType;
    uint32_t rank;
    uint32_t* dimensions;
    Qnn_ClientBuffer_t clientBuf;
    Qnn_ScaleOffset_t quantParams;
} QnnTensor_t;

// Function pointer signatures for Qualcomm QNN API
typedef Qnn_ErrorHandle_t (*QnnInterfaceGetProvidersFn)(const void*** providerList, uint32_t* numProviders);
typedef Qnn_ErrorHandle_t (*QnnBackendCreateFn)(Qnn_LogHandle_t logger, const void** config, Qnn_BackendHandle_t* backend);
typedef Qnn_ErrorHandle_t (*QnnBackendFreeFn)(Qnn_BackendHandle_t backend);
typedef Qnn_ErrorHandle_t (*QnnContextCreateFromBinaryFn)(
    Qnn_BackendHandle_t backend,
    Qnn_DeviceHandle_t device,
    const void** config,
    const void* binaryBuffer,
    uint64_t binaryBufferSize,
    Qnn_ContextHandle_t* context,
    Qnn_ProfileHandle_t profile
);
typedef Qnn_ErrorHandle_t (*QnnContextFreeFn)(Qnn_ContextHandle_t context, Qnn_ProfileHandle_t profile);
typedef Qnn_ErrorHandle_t (*QnnGraphRetrieveFn)(Qnn_ContextHandle_t context, const char* graphName, Qnn_GraphHandle_t* graph);
typedef Qnn_ErrorHandle_t (*QnnGraphExecuteFn)(
    Qnn_GraphHandle_t graph,
    const QnnTensor_t* inputs,
    uint32_t numInputs,
    QnnTensor_t* outputs,
    uint32_t numOutputs,
    Qnn_ProfileHandle_t profile,
    Qnn_SignalHandle_t signal
);

typedef Qnn_ErrorHandle_t (*QnnSystemContextCreateFn)(Qnn_SystemContextHandle_t* sysCtx);
typedef Qnn_ErrorHandle_t (*QnnSystemContextFreeFn)(Qnn_SystemContextHandle_t sysCtx);

/**
 * Thread-safe native Qualcomm Hexagon HTP execution wrapper.
 * Dynamically loads official QNN runtime libraries (libQnnHtp.so, libQnnSystem.so),
 * materializes graphs from genuine context binaries, and executes inference on HTP NPU.
 *
 * If hardware, drivers, or genuine context binaries are unavailable, fails clearly
 * without executing heuristic approximations, triggering safe CPU fallback.
 */
class QnnHtpBackend {
public:
    QnnHtpBackend()
        : m_backendHandle(nullptr),
          m_contextHandle(nullptr),
          m_graphHandle(nullptr),
          m_htpLibraryHandle(nullptr),
          m_systemLibraryHandle(nullptr),
          m_isHtpAvailable(false),
          m_backendCreateFn(nullptr),
          m_backendFreeFn(nullptr),
          m_contextCreateFromBinaryFn(nullptr),
          m_contextFreeFn(nullptr),
          m_graphRetrieveFn(nullptr),
          m_graphExecuteFn(nullptr) {}

    ~QnnHtpBackend() {
        release();
    }

    /**
     * Probes if Qualcomm Hexagon HTP / DSP runtime and driver interfaces are reachable.
     */
    bool isHtpSupported() {
        std::lock_guard<std::mutex> lock(m_mutex);

        // Check if library already loaded
        if (m_htpLibraryHandle != nullptr && m_isHtpAvailable) return true;

        // Check DSP RPC driver device nodes on Android kernel
        // /dev/adsprpc-smd: Legacy FastRPC
        // /dev/fastrpc-cdsp: Modern CDSP/HTP FastRPC device node (Snapdragon 8 Gen 2/3, 8 Elite SM8750)
        FILE* cdspNode = fopen("/dev/fastrpc-cdsp", "r");
        if (cdspNode != nullptr) {
            fclose(cdspNode);
        } else {
            FILE* adspNode = fopen("/dev/adsprpc-smd", "r");
            if (adspNode != nullptr) {
                fclose(adspNode);
            } else {
                LOGW("Qualcomm FastRPC device nodes (/dev/fastrpc-cdsp, /dev/adsprpc-smd) not found.");
                return false;
            }
        }

        // Check dynamic loading of libQnnHtp.so
        void* testHandle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
        if (testHandle != nullptr) {
            dlclose(testHandle);
            return true;
        }

        LOGW("libQnnHtp.so could not be opened by dynamic linker.");
        return false;
    }

    /**
     * Materializes execution graph on Hexagon HTP from a genuine QNN context binary.
     */
    bool initializeFromBinary(const uint8_t* binaryData, size_t binarySize) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (binaryData == nullptr || binarySize < 64) {
            LOGE("Invalid QNN context binary buffer (size: %zu)", binarySize);
            return false;
        }

        // Check binary magic ("QNNB" = 0x514E4E42)
        uint32_t magic = 0;
        memcpy(&magic, binaryData, sizeof(uint32_t));
        // Check both big-endian and little-endian representation
        if (magic != 0x514E4E42 && magic != 0x424E4E51) {
            LOGE("Context binary failed magic header validation: 0x%08X (expected 0x514E4E42)", magic);
            return false;
        }

        // 1. Dynamically load libQnnSystem.so if present
        m_systemLibraryHandle = dlopen("libQnnSystem.so", RTLD_NOW | RTLD_LOCAL);
        if (m_systemLibraryHandle != nullptr) {
            LOGI("libQnnSystem.so loaded successfully.");
        }

        // 2. Dynamically load libQnnHtp.so
        m_htpLibraryHandle = dlopen("libQnnHtp.so", RTLD_NOW | RTLD_LOCAL);
        if (m_htpLibraryHandle == nullptr) {
            const char* err = dlerror();
            LOGE("Failed to load libQnnHtp.so: %s", err ? err : "unknown");
            release();
            return false;
        }

        // 3. Resolve QNN entry symbols
        m_backendCreateFn = reinterpret_cast<QnnBackendCreateFn>(dlsym(m_htpLibraryHandle, "QnnBackend_create"));
        m_backendFreeFn = reinterpret_cast<QnnBackendFreeFn>(dlsym(m_htpLibraryHandle, "QnnBackend_free"));
        m_contextCreateFromBinaryFn = reinterpret_cast<QnnContextCreateFromBinaryFn>(dlsym(m_htpLibraryHandle, "QnnContext_createFromBinary"));
        m_contextFreeFn = reinterpret_cast<QnnContextFreeFn>(dlsym(m_htpLibraryHandle, "QnnContext_free"));
        m_graphRetrieveFn = reinterpret_cast<QnnGraphRetrieveFn>(dlsym(m_htpLibraryHandle, "QnnGraph_retrieve"));
        m_graphExecuteFn = reinterpret_cast<QnnGraphExecuteFn>(dlsym(m_htpLibraryHandle, "QnnGraph_execute"));

        if (!m_backendCreateFn || !m_contextCreateFromBinaryFn || !m_graphRetrieveFn || !m_graphExecuteFn) {
            LOGE("Failed to resolve mandatory Qualcomm QNN API symbols in libQnnHtp.so");
            release();
            return false;
        }

        // 4. Create QNN Backend
        Qnn_ErrorHandle_t err = m_backendCreateFn(nullptr, nullptr, &m_backendHandle);
        if (err != QNN_SUCCESS || m_backendHandle == nullptr) {
            LOGE("QnnBackend_create failed on Hexagon HTP with error code: %u", err);
            release();
            return false;
        }

        // 5. Create Context from Binary
        err = m_contextCreateFromBinaryFn(
            m_backendHandle,
            nullptr, // Default device
            nullptr, // Default config
            binaryData,
            static_cast<uint64_t>(binarySize),
            &m_contextHandle,
            nullptr  // Profile handle
        );
        if (err != QNN_SUCCESS || m_contextHandle == nullptr) {
            LOGE("QnnContext_createFromBinary failed on Hexagon HTP with error code: %u", err);
            release();
            return false;
        }

        // 6. Retrieve compiled graph
        const char* graphNames[] = {"sparkshield_1d_cnn", "sparkshield_1d_cnn_htp", "model"};
        m_graphHandle = nullptr;
        for (const char* name : graphNames) {
            err = m_graphRetrieveFn(m_contextHandle, name, &m_graphHandle);
            if (err == QNN_SUCCESS && m_graphHandle != nullptr) {
                LOGI("Retrieved QNN graph: %s", name);
                break;
            }
        }

        if (m_graphHandle == nullptr) {
            LOGE("Could not retrieve SparkShield graph handle from materialized context");
            release();
            return false;
        }

        m_isHtpAvailable = true;
        LOGI("Qualcomm Hexagon HTP backend successfully initialized with genuine graph.");
        return true;
    }

    /**
     * Executes hardware inference on Hexagon HTP.
     * Returns execution latency in microseconds on success, or negative error code on failure.
     * Never performs heuristic approximations.
     */
    int64_t execute(const float* inputBuffer, float* outputBuffer, size_t inputElements = 128, size_t outputElements = 4) {
        std::lock_guard<std::mutex> lock(m_mutex);

        if (!m_isHtpAvailable || m_graphHandle == nullptr || !m_graphExecuteFn) {
            LOGE("execute called on uninitialized or unavailable HTP backend");
            return -1;
        }

        if (inputBuffer == nullptr || outputBuffer == nullptr) {
            LOGE("Null input/output buffer passed to execute");
            return -2;
        }

        if (inputElements != 128 || outputElements != 4) {
            LOGE("Invalid tensor shape: expected input 128 floats, output 4 floats. Got %zu, %zu",
                 inputElements, outputElements);
            return -3;
        }

        // Configure input tensor [1, 1, 128]
        uint32_t inDims[3] = {1, 1, 128};
        QnnTensor_t inTensor;
        memset(&inTensor, 0, sizeof(QnnTensor_t));
        inTensor.id = 0;
        inTensor.name = "input";
        inTensor.dataType = QNN_DATATYPE_FLOAT_32;
        inTensor.rank = 3;
        inTensor.dimensions = inDims;
        inTensor.clientBuf.data = const_cast<float*>(inputBuffer);
        inTensor.clientBuf.dataSize = 128 * sizeof(float);

        // Configure output tensor [1, 4]
        uint32_t outDims[2] = {1, 4};
        QnnTensor_t outTensor;
        memset(&outTensor, 0, sizeof(QnnTensor_t));
        outTensor.id = 1;
        outTensor.name = "logits";
        outTensor.dataType = QNN_DATATYPE_FLOAT_32;
        outTensor.rank = 2;
        outTensor.dimensions = outDims;
        outTensor.clientBuf.data = outputBuffer;
        outTensor.clientBuf.dataSize = 4 * sizeof(float);

        // Execute inference on Hexagon HTP
        auto t0 = std::chrono::high_resolution_clock::now();

        Qnn_ErrorHandle_t err = m_graphExecuteFn(
            m_graphHandle,
            &inTensor,
            1,
            &outTensor,
            1,
            nullptr,
            nullptr
        );

        auto t1 = std::chrono::high_resolution_clock::now();

        if (err != QNN_SUCCESS) {
            LOGE("QnnGraph_execute failed on Hexagon HTP with error: %u", err);
            return -4;
        }

        int64_t latencyUs = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
        return latencyUs >= 0 ? latencyUs : 0;
    }

    /**
     * Safely releases all acquired QNN backend, context, and dynamic library handles.
     */
    void release() {
        if (m_contextHandle != nullptr && m_contextFreeFn != nullptr) {
            m_contextFreeFn(m_contextHandle, nullptr);
            m_contextHandle = nullptr;
        }

        if (m_backendHandle != nullptr && m_backendFreeFn != nullptr) {
            m_backendFreeFn(m_backendHandle);
            m_backendHandle = nullptr;
        }

        m_graphHandle = nullptr;

        if (m_htpLibraryHandle != nullptr) {
            dlclose(m_htpLibraryHandle);
            m_htpLibraryHandle = nullptr;
        }

        if (m_systemLibraryHandle != nullptr) {
            dlclose(m_systemLibraryHandle);
            m_systemLibraryHandle = nullptr;
        }

        m_backendCreateFn = nullptr;
        m_backendFreeFn = nullptr;
        m_contextCreateFromBinaryFn = nullptr;
        m_contextFreeFn = nullptr;
        m_graphRetrieveFn = nullptr;
        m_graphExecuteFn = nullptr;

        m_isHtpAvailable = false;
    }

    bool isHtpAvailable() const {
        return m_isHtpAvailable && (m_graphHandle != nullptr);
    }

private:
    std::mutex m_mutex;
    Qnn_BackendHandle_t m_backendHandle;
    Qnn_ContextHandle_t m_contextHandle;
    Qnn_GraphHandle_t m_graphHandle;

    void* m_htpLibraryHandle;
    void* m_systemLibraryHandle;
    bool m_isHtpAvailable;

    QnnBackendCreateFn m_backendCreateFn;
    QnnBackendFreeFn m_backendFreeFn;
    QnnContextCreateFromBinaryFn m_contextCreateFromBinaryFn;
    QnnContextFreeFn m_contextFreeFn;
    QnnGraphRetrieveFn m_graphRetrieveFn;
    QnnGraphExecuteFn m_graphExecuteFn;
};

#endif // SPARKSHIELD_QNN_API_H
