FROM postgres:17.11-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24

# The upstream entrypoint invokes "gosu postgres" only to drop privileges.
# su-exec has the same invocation form without embedding an outdated Go runtime.
RUN apk upgrade --no-cache \
    && apk add --no-cache su-exec \
    && ln -sf /sbin/su-exec /usr/local/bin/gosu \
    && test "$(gosu postgres id -u)" = "70"
