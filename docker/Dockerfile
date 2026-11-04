# syntax=docker/dockerfile:1.7
FROM node:22-bookworm-slim AS node-runtime

FROM mcr.microsoft.com/powershell:7.5-debian-12

ARG ANDROID_CMDLINE_TOOLS_SHA256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/usr/lib/jvm/java-17-openjdk-amd64/bin:$PATH

COPY --from=node-runtime /usr/local/bin/node /usr/local/bin/node

RUN apt-get update \
    && apt-get install --no-install-recommends -y ca-certificates curl git \
       openjdk-17-jdk-headless python3 tar unzip libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p "$ANDROID_HOME/cmdline-tools" /work /gradle-cache /tmp/home \
    && chmod 1777 /work /gradle-cache /tmp/home \
    && curl --fail --location --silent --show-error \
       https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip \
       --output /tmp/android-cmdline-tools.zip \
    && echo "$ANDROID_CMDLINE_TOOLS_SHA256  /tmp/android-cmdline-tools.zip" | sha256sum --check --status \
    && mkdir -p /tmp/android-tools \
    && unzip -q /tmp/android-cmdline-tools.zip -d /tmp/android-tools \
    && mv /tmp/android-tools/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest" \
    && rm -rf /tmp/android-tools /tmp/android-cmdline-tools.zip

RUN --mount=type=secret,id=android_licenses,required=true \
    tar -xf /run/secrets/android_licenses -C "$ANDROID_HOME" \
    && sdkmanager --sdk_root="$ANDROID_HOME" \
       'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' \
    && rm -rf /root/.android

ENV SDKMANAGER=/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager \
    GRADLE_USER_HOME=/gradle-cache \
    ANDROID_USER_HOME=/gradle-cache/android-user \
    HOME=/tmp/home

COPY entrypoint.sh /usr/local/bin/streaming-radio-entrypoint
RUN chmod 0755 /usr/local/bin/streaming-radio-entrypoint

ENTRYPOINT ["/usr/local/bin/streaming-radio-entrypoint"]
