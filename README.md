# CF优选IP (cf-ip-picker)

Cloudflare IP 优选工具 —— 逆向重写版。

原版 APK(`com.cf.ip`)核心是 Go JNI 库,本项目用 **纯 Kotlin + Jetpack Compose** 重写,去掉 Go/NDK 依赖。

## 功能

- 拉取 Cloudflare 全部 IPv4/IPv6 段(`baipiao.eu.org`)
- 随机采样 IP
- TCP RTT 延迟测试(443 端口)
- 镜像站下载测速
- 按(延迟, 带宽)排序,选出最优 CF IP

## 数据源 API(逆向自原版)

| 端点 | 说明 |
|---|---|
| `GET https://www.baipiao.eu.org/cloudflare/ips-v4` | CF IPv4 段列表 |
| `GET https://www.baipiao.eu.org/cloudflare/ips-v6` | CF IPv6 段列表 |
| `GET https://www.baipiao.eu.org/cloudflare/locations` | 301 个机房坐标 |
| `GET https://www.baipiao.eu.org/cloudflare/url` | 测速下载文件路径 |

## 构建

```bash
./gradlew assembleRelease
```

或使用 GitHub Actions(workflow_dispatch,输入 version 自动发 Release)。

## 使用

1. 安装 APK
2. 点击「开始扫描」
3. 等待扫描完成,查看按延迟/带宽排序的最优 CF IP 列表
