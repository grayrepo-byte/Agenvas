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
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline

# The runtime filesystem is read-only and its writable tmpfs is intentionally noexec. Extract the
# architecture-specific JNI libraries into the image so ONNX Runtime never executes from tmpfs.
RUN case "$(uname -m)" in \
        x86_64) ort_arch=x64 ;; \
        aarch64|arm64) ort_arch=aarch64 ;; \
        *) echo "Unsupported ONNX Runtime architecture: $(uname -m)" >&2; exit 1 ;; \
    esac \
    && mkdir -p /workspace/onnxruntime-native \
    && cd /workspace/onnxruntime-native \
    && jar --extract \
        --file "/root/.m2/repository/com/microsoft/onnxruntime/onnxruntime/${ONNXRUNTIME_VERSION}/onnxruntime-${ONNXRUNTIME_VERSION}.jar" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime.so" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime4j_jni.so" \
    && mv "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime.so" \
        "ai/onnxruntime/native/linux-${ort_arch}/libonnxruntime4j_jni.so" . \
    && rm -r ai

COPY backend/src src
RUN ./mvnw --batch-mode --no-transfer-progress verify -DskipITs \
    -Dagenvas.test.depth-model="${DEPTH_MODEL_FILE}" \
    -Donnxruntime.native.path=/workspace/onnxruntime-native

FROM eclipse-temurin:21.0.9_10-jre-noble@sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56568

RUN sed -i 's|http://ports.ubuntu.com|https://ports.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get -o Acquire::Retries=5 -o APT::Update::Error-Mode=any update \
    && apt-get upgrade --yes \
    && apt-get install --yes --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 101 agenvas \
    && useradd --system --uid 100 --gid agenvas --home-dir /opt/agenvas \
        --shell /usr/sbin/nologin agenvas \
    && mkdir -p /opt/agenvas/data /opt/agenvas/tmp /opt/agenvas/models \
        /opt/agenvas/lib/onnxruntime \
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

USER agenvas
EXPOSE 8080
ENTRYPOINT ["java", "-Djava.io.tmpdir=/opt/agenvas/tmp", "-Donnxruntime.native.path=/opt/agenvas/lib/onnxruntime", "-jar", "/opt/agenvas/app.jar"]
