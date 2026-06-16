---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '67ca17d5-28e6-433f-921d-e4b622173a21'
  PropagateID: '67ca17d5-28e6-433f-921d-e4b622173a21'
  ReservedCode1: 'befb7a52-8cee-4ddd-a474-2f226014e8cf'
  ReservedCode2: 'befb7a52-8cee-4ddd-a474-2f226014e8cf'
---

# UNMHook - iOS 编译指南

## 前置条件

- macOS + Xcode Command Line Tools（或 GitHub Actions CI）
- iOS SDK 14.0+
- **无需 Theos、Logos、CydiaSubstrate** — 纯 ObjC + objc/runtime 实现

## 编译步骤

```bash
# 一行命令编译（无需 Theos）
clang++ \
  -dynamiclib \
  -arch arm64 \
  -isysroot $(xcrun --sdk iphoneos --show-sdk-path) \
  -miphoneos-version-min=14.0 \
  -framework Foundation \
  -framework UIKit \
  -fobjc-arc \
  -fmodules \
  -std=c++17 \
  -install_name "@rpath/libUNMHook.dylib" \
  -o UNMHook.dylib \
  Tweak.x
```

## 修改配置

编辑 `Tweak.x` 顶部配置区域：
- `MUSIC_API_URL`: 音源 API 地址（默认 GD studio）
- `BITRATE`: 码率，999=FLAC, 320=320kbps, 128=128kbps

## 部署方式

### 方式1：巨魔（TrollStore）
```
1. 下载 UNMHook.dylib
2. 使用 inject 工具注入到网易云音乐 .ipa
3. 通过 TrollStore 安装修改后的 .ipa
4. 杀掉并重启网易云音乐
```

### 方式2：越狱（Dopamine / unc0ver）
```
1. 复制 UNMHook.dylib 到 /Library/MobileSubstrate/DynamicLibraries/
2. 复制 UNMHook.plist 到同一目录
3. killall NeteaseMusic
```

## CI 构建

推送到 `main` 分支的 `ios/**` 路径变更会自动触发 GitHub Actions 编译。
编译产物会上传为 artifact 并创建 GitHub Release。

## 调试

查看日志：
```bash
# macOS
idevicesyslog | grep UNMHook

# 或通过 Console.app 过滤 UNMHook
```

## 架构说明

| 组件 | 技术 |
|------|------|
| 方法 Hook | `method_exchangeImplementations` (objc/runtime) |
| 同步 HTTP | `NSURLSession` + `dispatch_semaphore_t` |
| JSON 解析 | `NSJSONSerialization` |
| 初始化 | `__attribute__((constructor))` |

## 故障排除

| 问题 | 原因 | 解决 |
|------|------|------|
| 音乐仍然灰色 | Hook 未生效 | 检查日志中是否出现 "Hook installed" |
| 网络连接失败 | API 不可用 | 检查 MUSIC_API_URL 是否可达 |
| APP 闪退 | dylib 注入失败 | 检查架构是否匹配（arm64） |
| 部分歌曲无法解锁 | 音源匹配失败 | 查看 UNMHook 日志中的 API 返回 |