# Native libraries

## `libv2ray.aar` (required, not in git)

The Xray core AAR is ~59 MB, so it is not stored in this repository.

Download **v26.8.20** from [AndroidLibXrayLite releases](https://github.com/2dust/AndroidLibXrayLite/releases) and place the file here:

```
app/libs/libv2ray.aar
```

Direct link for the version this tree was built against:

```
https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray.aar
```

PowerShell:

```powershell
Invoke-WebRequest -Uri "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.8.20/libv2ray.aar" -OutFile "app/libs/libv2ray.aar"
```

## `libhev-socks5-tunnel.so`

Per-ABI copies under `arm64-v8a/`, `armeabi-v7a/`, `x86/`, and `x86_64/` are tracked. They are loaded by `TProxyService`.
