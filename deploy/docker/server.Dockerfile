FROM maven:3.9.12-eclipse-temurin-21-noble@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS build

ARG DEPTH_MODEL_URL=https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/c70d1ddbcd93c9bda8098268cc3554adf5e8dd4f/onnx/model_int8.onnx
ARG DEPTH_MODEL_SHA256=01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001
ARG DEPTH_LICENSE_URL=https://raw.githubusercontent.com/DepthAnything/Depth-Anything-V2/a561b849ebae10a6f5ef49e26c83cbbcd36c71bf/LICENSE
ARG DEPTH_LICENSE_SHA256=c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4
ARG DEPTH_MODEL_FILE=/workspace/depth-model/depth-anything-v2-small-int8.onnx
ARG ONNXRUNTIME_VERSION=1.30.0

RUN mkdir -p /workspace/depth-model \
    && curl --fail --location --silent --show-error --retry 5 --retry-all-errors \
        --retry-delay 2 --output "${DEPTH_MODEL_FILE}" "${DEPTH_MODEL_URL}" \
    && echo "${DEPTH_MODEL_SHA256}  ${DEPTH_MODEL_FILE}" | sha256sum -c - \
    && curl --fail --location --silent --show-error --retry 5 --retry-all-errors \
        --retry-delay 2 \
        --output /workspace/depth-model/LICENSE "${DEPTH_LICENSE_URL}" \
    && echo "${DEPTH_LICENSE_SHA256}  /workspace/depth-model/LICENSE" | sha256sum -c -

WORKDIR /workspace/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./

# Local builds retain a Maven download cache across pom.xml changes. CI defaults to the
# dependency layer: GHA exports layer caches, not the contents of BuildKit cache mounts.
ARG MAVEN_CACHE_MOUNT=false
RUN if [ "${MAVEN_CACHE_MOUNT}" = false ]; then \
      ./mvnw --batch-mode --no-transfer-progress dependency:go-offline; \
    elif [ "${MAVEN_CACHE_MOUNT}" != true ]; then \
      echo 'MAVEN_CACHE_MOUNT must be true or false' >&2; exit 1; \
    fi

COPY backend/src/main src/main
COPY backend/src/jooq src/jooq
# The explicit MCP catalog is packaged as a classpath resource by Maven, also in source image builds.
COPY contracts/mcp /workspace/contracts/mcp
RUN --mount=type=cache,id=agenvas-maven,target=/root/.m2-cache,sharing=locked \
    if [ "${MAVEN_CACHE_MOUNT}" = true ]; then export MAVEN_USER_HOME=/root/.m2-cache; fi \
    && ./mvnw --batch-mode --no-transfer-progress \
        -Dmaven.repo.local="${MAVEN_USER_HOME:-/root/.m2}/repository" package -Dmaven.test.skip=true

# The runtime filesystem is read-only and its writable tmpfs is intentionally noexec. Extract the
# architecture-specific JNI libraries into the image so ONNX Runtime never executes from tmpfs.
RUN --mount=type=cache,id=agenvas-maven,target=/root/.m2-cache,sharing=locked \
    if [ "${MAVEN_CACHE_MOUNT}" = true ]; then export MAVEN_USER_HOME=/root/.m2-cache; fi \
    && case "$(uname -m)" in \
        x86_64) ort_arch=x64 ;; \
        aarch64|arm64) ort_arch=aarch64 ;; \
        *) echo "Unsupported ONNX Runtime architecture: $(uname -m)" >&2; exit 1 ;; \
    esac \
    && mkdir -p /workspace/onnxruntime-native \
    && cd /workspace/onnxruntime-native \
    && jar --extract \
        --file "${MAVEN_USER_HOME:-/root/.m2}/repository/com/microsoft/onnxruntime/onnxruntime/${ONNXRUNTIME_VERSION}/onnxruntime-${ONNXRUNTIME_VERSION}.jar" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime.so" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime4j_jni.so" \
    && mv "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime.so" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime4j_jni.so" . \
    && rm -r ai

# CI retains one native-architecture model/JNI smoke test without rerunning the unit suite.
# Ordinary source builds only package the application; the full suites belong to CI jobs.
COPY backend/src/test src/test
ARG VERIFY_DEPTH_MODEL=false
RUN --mount=type=cache,id=agenvas-maven,target=/root/.m2-cache,sharing=locked \
    if [ "${VERIFY_DEPTH_MODEL}" = true ]; then \
      if [ "${MAVEN_CACHE_MOUNT}" = true ]; then export MAVEN_USER_HOME=/root/.m2-cache; fi; \
      ./mvnw --batch-mode --no-transfer-progress test \
        -Dmaven.repo.local="${MAVEN_USER_HOME:-/root/.m2}/repository" \
        '-Dtest=LocalImageProcessorAdapterTest#runsConfiguredDepthAnythingModel' \
        -Dagenvas.test.depth-model="${DEPTH_MODEL_FILE}" \
        -Donnxruntime.native.path=/workspace/onnxruntime-native; \
    fi

# Source is content-pinned and compiled for the target Linux architecture. No model is fetched here.
FROM maven:3.9.12-eclipse-temurin-21-noble@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS whisper-build
ENV WHISPER_COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
RUN apt-get update && apt-get install --yes --no-install-recommends cmake g++ git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && git init /workspace/whisper \
    && git -C /workspace/whisper remote add origin https://github.com/ggml-org/whisper.cpp.git \
    && git -C /workspace/whisper fetch --depth 1 origin "${WHISPER_COMMIT}" \
    && git -C /workspace/whisper checkout --detach FETCH_HEAD \
    && test "$(git -C /workspace/whisper rev-parse HEAD)" = "${WHISPER_COMMIT}" \
    && cmake -S /workspace/whisper -B /workspace/whisper/build -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DGGML_BACKEND_DL=OFF \
        -DGGML_SSE42=OFF -DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_BMI2=OFF -DGGML_FMA=OFF -DGGML_F16C=OFF \
        -DWHISPER_BUILD_IS_DEV=OFF \
        -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF \
    && cmake --build /workspace/whisper/build --target whisper-cli --parallel 2
COPY deploy/docker/asr-launcher.c /workspace/asr-launcher.c
RUN cc -O2 -Wall -Wextra -Werror /workspace/asr-launcher.c -o /workspace/asr-launcher

FROM eclipse-temurin:21.0.9_10-jre-noble@sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56568 AS runtime

RUN sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=5 -o APT::Update::Error-Mode=any update \
    && apt-get upgrade --yes \
    && apt-get install --yes --no-install-recommends ffmpeg fonts-noto-cjk libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 101 agenvas \
    && useradd --system --uid 100 --gid agenvas --home-dir /opt/agenvas \
        --shell /usr/sbin/nologin agenvas \
    && mkdir -p /opt/agenvas/data /opt/agenvas/tmp /opt/agenvas/models \
        /opt/agenvas/lib/onnxruntime /opt/agenvas/lib/whisper /opt/agenvas/bin /opt/agenvas/asr \
        /opt/agenvas/licenses/depth-anything-v2-small \
    && chown -R agenvas:agenvas /opt/agenvas

WORKDIR /opt/agenvas
COPY --from=build --chown=agenvas:agenvas /workspace/backend/target/agenvas-server-*.jar app.jar
COPY --from=build --chown=agenvas:agenvas \
    /workspace/depth-model/depth-anything-v2-small-int8.onnx \
    /opt/agenvas/models/depth-anything-v2-small-int8.onnx
COPY --from=build --chown=agenvas:agenvas /workspace/depth-model/LICENSE \
    /opt/agenvas/licenses/depth-anything-v2-small/LICENSE
COPY --from=build --chown=agenvas:agenvas /workspace/onnxruntime-native/ \
    /opt/agenvas/lib/onnxruntime/

COPY --from=whisper-build --chown=agenvas:agenvas /workspace/whisper/build/bin/whisper-cli /opt/agenvas/lib/whisper/whisper-cli
COPY --from=whisper-build --chown=agenvas:agenvas /workspace/asr-launcher /opt/agenvas/bin/whisper-cli
COPY --from=whisper-build /workspace/whisper/LICENSE /opt/agenvas/licenses/whisper.cpp/LICENSE

COPY --chmod=755 deploy/docker/server-entrypoint.sh /usr/local/bin/agenvas-entrypoint

USER agenvas
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/agenvas-entrypoint"]
CMD ["java", "-Djava.io.tmpdir=/opt/agenvas/tmp", "-Donnxruntime.native.path=/opt/agenvas/lib/onnxruntime", "-jar", "/opt/agenvas/app.jar"]
