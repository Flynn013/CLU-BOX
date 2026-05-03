# Embedded BusyBox Binary

This directory must contain a statically-linked, **arm64-v8a** BusyBox binary
named `busybox-arm64-v8a` (no extension). It is extracted to internal storage
on first launch by `com.google.ai.edge.gallery.data.busybox.BusyBoxBridge`
and chmod +x'd so it can be invoked via Android `ProcessBuilder`.

Recommended source: https://busybox.net/downloads/binaries/

Build/curl example (run on a build machine, not on device):

```bash
curl -L -o busybox-arm64-v8a \
  https://busybox.net/downloads/binaries/1.35.0-aarch64-linux-musl/busybox
sha256sum busybox-arm64-v8a
mv busybox-arm64-v8a Android/src/app/src/main/assets/busybox/
```

The binary is intentionally absent from the git repository to avoid bundling
prebuilt executables. A CI pipeline or developer must drop the binary here
before producing a release APK.
