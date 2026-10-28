#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
export PATH="$(go env GOPATH)/bin:$PATH"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
version="$(git describe --tags --always)-skybox"
tags='with_quic,with_wireguard,with_utls,with_naive_outbound,with_clash_api,with_usbip,with_openvpn,with_openconnect,badlinkname,tfogo_checklinkname0,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird'
mkdir -p android-ui/app/libs
gomobile bind -o android-ui/app/libs/libbox.aar -target android/arm64 -androidapi 24 \
  -javapkg io.nekohasekai -libname box -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=$version -checklinkname=0 -s -w -buildid=" \
  -tags "$tags" ./experimental/libbox
