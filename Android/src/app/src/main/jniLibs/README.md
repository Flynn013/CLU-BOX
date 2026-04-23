# jniLibs — Native Binary Smuggling Directory

This directory holds the pre-compiled **PRoot** and **Bash** binaries renamed as
`.so` files so that the Android package manager installs them with **execute
permission** into `applicationInfo.nativeLibraryDir` (the W^X bypass).

## Required files per ABI

| File | Description | Source |
|------|-------------|--------|
| `libproot.so` | PRoot 5.x static binary | https://github.com/termux/proot (Android NDK build) |
| `libbash.so` | Bash 5.x static binary | https://github.com/termux/termux-packages or UserLAnd |

## How to obtain binaries

### Option A — Extract from Termux bootstrap
```bash
# Download the Termux bootstrap zip for the target ABI
curl -L "https://github.com/termux/termux-packages/releases/latest/download/bootstrap-aarch64.zip" -o bootstrap.zip
unzip bootstrap.zip
# Find and rename the binaries
cp usr/bin/proot  arm64-v8a/libproot.so
cp usr/bin/bash   arm64-v8a/libbash.so
```

### Option B — Extract from UserLAnd APK
```bash
apktool d UserLAnd.apk -o userland-out
cp userland-out/lib/arm64-v8a/libproot.so   arm64-v8a/libproot.so
cp userland-out/lib/arm64-v8a/libbash.so    arm64-v8a/libbash.so
```

### Option C — Compile with NDK
See `../../cpp/CMakeLists.txt` for the NDK toolchain build.

## ABI subdirectories

- `arm64-v8a/` — 64-bit ARM (most modern Android phones and the Android XR glass)
- `x86_64/`   — 64-bit x86 (emulators)

## How `NativeShellBridge` uses these files

At runtime, `NativeShellBridge(context)` looks up:
```
context.applicationInfo.nativeLibraryDir + "/libproot.so"
context.applicationInfo.nativeLibraryDir + "/libbash.so"
```

Both files are already executable (the package manager set the bit). The bridge
launches PRoot with a chroot jail and bind-mounts `/dev`, `/proc`, and `/sys`.
See `NativeShellBridge.kt` for the full implementation.
