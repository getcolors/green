FROM alpine:3.22 AS kubectl
ARG TARGETARCH
ARG KUBECTL_VERSION=v1.37.0
RUN apk add --no-cache curl \
 && arch="${TARGETARCH:-$(uname -m)}" \
 && case "$arch" in x86_64) arch=amd64 ;; aarch64) arch=arm64 ;; amd64|arm64) ;; *) exit 1 ;; esac \
 && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${arch}/kubectl" -o /kubectl \
 && curl -fsSL "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${arch}/kubectl.sha256" -o /kubectl.sha256 \
 && echo "$(cat /kubectl.sha256)  /kubectl" | sha256sum -c - \
 && chmod +x /kubectl
FROM babashka/babashka:1.12.218
COPY --from=kubectl /kubectl /usr/local/bin/kubectl
WORKDIR /app
COPY src /app/src
COPY examples/kubernetes /app/examples/kubernetes
ENTRYPOINT ["/app/examples/kubernetes/controller"]
