#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
export PATH="$(go env GOPATH)/bin:$PATH"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
version="$(git describe --tags --always)-skybox"
# Size-optimized feature set (SkyBox 3.0): drop OpenVPN/OpenConnect/USBIP/Tailscale and the
# now-pointless tailscale ts_omit_* tags. Keep quic/wireguard/utls/naive/clash_api (common protocols).
tags='with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,badlinkname,tfogo_checklinkname0'
mkdir -p android-ui/app/libs
gomobile bind -o android-ui/app/libs/libbox.aar -target android/arm64 -androidapi 24 \
  -javapkg io.nekohasekai -libname box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=$version -checklinkname=0 -s -w -buildid=" \
  -tags "$tags" ./experimental/libbox
