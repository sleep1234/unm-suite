---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'b265e160-9dad-48d8-8f76-8a7425ed8688'
  PropagateID: 'b265e160-9dad-48d8-8f76-8a7425ed8688'
  ReservedCode1: '87c5e95f-4ebf-4d8a-bada-fd590a044f8d'
  ReservedCode2: '87c5e95f-4ebf-4d8a-bada-fd590a044f8d'
---

# UNMHook - iOS 编译指南

## 前置条件

- macOS (或 Linux + Theos)
- Theos 已安装 (`sudo install_theos.sh`)
- iOS SDK 15.0+
- Xcode Command Line Tools

## 编译步骤

```bash
# 1. 设置 Theos 环境变量
export THEOS=~/theos

# 2. 修改配置
# 编辑 Tweak.x 顶部的配置区域：
# - PROXY_HOST: 改为你的 NAS IP 或域名
# - PROXY_HTTP_PORT / PROXY_HTTPS_PORT: 改为 UNM 服务端口
# - WORK_MODE: 0=代理模式（推荐），1=URL重写模式

# 3. 编译
cd UNMHook
make clean && make package

# 编译产物在 packages/ 目录下
```

## 部署方式

### 方式1：巨魔（TrollStore）
```bash
# 1. 将 .deb 解压获取 dylib
dpkg-deb -x com.unm.unmhook_1.0.0_iphoneos-arm.deb unm-extract/
cp unm-extract/Library/MobileSubstrate/DynamicLibraries/UNMHook.dylib .

# 2. 使用 TrollStore Helper 注入到网易云音乐
# 或者直接在 IPA 中注入 dylib 后通过 TrollStore 安装
```

### 方式2：越狱
```bash
# 直接安装 deb
dpkg -i com.unm.unmhook_1.0.0_iphoneos-arm.deb

# 重启网易云音乐
killall NeteaseMusic
```

## 调试

查看日志：
```bash
# macOS
idevicesyslog | grep UNMHook

# 或通过 Console.app 过滤 UNMHook
```

## 故障排除

| 问题 | 原因 | 解决 |
|------|------|------|
| 音乐仍然灰色 | 代理未生效 | 检查 PROXY_HOST 和端口配置 |
| 网络连接失败 | HTTPS 证书信任问题 | 确认 NAS 已部署 UNM 并启用 HTTPS |
| APP 闪退 | dylib 注入失败 | 检查架构是否匹配（arm64） |
| 部分歌曲无法解锁 | 音源匹配失败 | UNM 服务端日志排查 |