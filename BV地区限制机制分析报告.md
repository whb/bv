# BV 中国大陆地区限制机制分析报告

> 分析对象：`C:/code/bv`（BV / Bug Video，第三方哔哩哔哩客户端，Android 移动端 + TV）
> 分析时间：2026-10-13

## 一、结论摘要

BV 无法在中国大陆智能电视上使用，是**代码内主动实现的地区封锁**，共 6 处：

| # | 文件 | 作用 |
|---|------|------|
| 1 | `app/shared/src/main/kotlin/dev/aaa1115910/bv/util/NetworkUtil.kt` | 地区检测（Cloudflare `loc=CN` 判定大陆） |
| 2 | `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/activities/MainActivity.kt` | TV 端启动门禁：命中大陆 → 进入封锁页 |
| 3 | `app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/activities/MainActivity.kt` | 移动端启动门禁（同样拦截大陆） |
| 4 | `app/tv/src/main/kotlin/dev/aaa1115910/bv/tv/screens/RegionBlockScreen.kt` | TV 封锁页 UI + 退出进程 |
| 5 | `app/mobile/src/main/kotlin/dev/aaa1115910/bv/mobile/screen/RegionBlockScreen.kt` | 移动端封锁页 UI + 退出进程 |
| 6 | `app/shared/src/main/res/values/strings.xml`（141–148 行）+ `README.md`（17–19 行） | 封锁文案与项目声明 |

## 二、限制机制详解

### 1. 地区检测 `NetworkUtil.isMainlandChina()`（NetworkUtil.kt:34–59）

```kotlin
private val locCheckUrls = listOf(
    "https://www.cloudflare.com/cdn-cgi/trace",
    "https://1.1.1.1/cdn-cgi/trace"
)
...
require(networkCheckResult["loc"] != "CN") { "BV doesn't support use in mainland China" }
false
// runCatching{}.getOrDefault(true)
```

- 通过 Cloudflare `/cdn-cgi/trace` 返回的 `loc` 字段判断出口 IP 归属；
- `loc=CN` → 抛异常 → `getOrDefault(true)` → 判定为大陆；
- **fail-closed 设计**：网络异常/超时同样返回 `true`（按大陆处理，直接拦截）；
- 两个检测 URL 并发请求，`select` 取最先返回的结果。

### 2. 启动门禁（TV MainActivity.kt:44、54–66）

```kotlin
isMainlandChina = NetworkUtil.isMainlandChina()
...
} else if (isMainlandChina) {
    RegionBlockScreen()
} else {
    MainScreen()
}
```

移动端 MainActivity.kt:31–48 逻辑相同，命中即渲染 `RegionBlockScreen`。

### 3. 封锁页行为

- TV 版：全屏警告页（"不支持的使用地区"），二维码内容为"扫码也没有用的！"，**任何按键即 `finish()` + `exitProcess(0)` 强杀进程**；
- 移动版：警告页 + "退出应用"按钮，同样强杀进程；
- 用户无任何绕过入口。

## 三、BV 当前调用 B 站接口的凭据方式

`bili-api/http/util/BiliAppConf.kt`：`MOBI_APP = "android_hd"`、`APP_BUILD_CODE = 2020100`、`CHANNEL = "yingyongbao"` —— 即**伪装成官方"哔哩哔哩HD"客户端身份**调用非公开接口（含 TV 端 grpc 接口）。

这属于未经授权的第三方接入，与哔哩哔哩开放平台的授权机制无关。

## 四、哔哩哔哩官方授权机制调研结果

1. **开放平台（open.bilibili.com）**：面向网页/移动应用的 OAuth 2.0 授权。开发者入驻、创建应用、获取 `client_id`/`secret`，用户授权后可调用其申请范围内的开放接口（用户信息等）。
   - 不覆盖 TV 端内容与播放接口。
2. **TV 端合规路径**：中国大陆互联网电视（OTT）受牌照管理，全国仅约 7 家持牌集成播控平台（南方新媒体、CIBN、未来电视、百视通、华数、芒果、银河等）。B 站电视内容唯一合规出口是持牌合作的**云视听小电视**（南方新媒体 × 哔哩哔哩，2018 年上线）。
   - 这是商业合作，无法通过注册开发者账号获得。

## 五、为什么"删掉地区判断代码"不能使其在大陆合法

1. **监管层面**：大陆智能电视端视听服务必须接入持牌方的集成播控平台；无牌照的 TV 应用属于违规 OTT 服务，本身就是广电部门整治对象。
2. **版权层面**：B 站 TV 端内容版权仅授权给持牌合作的云视听小电视，第三方客户端在电视端分发即侵权。
3. **平台规则**：BV 使用的 android_hd 身份与非公开接口本身未经授权，即便放开地区判断，也只是把违规使用范围扩大到大陆电视端。

地区判断代码是作者设立的**合规护栏**，不是技术缺陷；改掉它只会制造一个"能启动但不受法律保护"的应用。

## 六、合法路径建议

- 大陆智能电视用户：使用官方**云视听小电视**（应用商店可下载）。
- 若确要商业化运营 TV 端 B 站内容：与持牌方（如南方新媒体）及哔哩哔哩洽谈合作，以持牌播控平台下的合规产品形态上线。
- 若开发非 TV 端第三方应用：入驻[哔哩哔哩开放平台](https://open.bilibili.com)，申请 client_id/secret，按 OAuth 2.0 流程接入授权接口。

## 七、本次改造记录（Demo 版）

经与需求方确认：本次为**个人开发者的本地技术验证 Demo**，用于验证可行性，后续再接入持牌方平台。基于该前提，已执行以下改造（commit 前状态）：

| 操作 | 文件 |
|---|---|
| 摘除地区门禁 | `app/tv/.../activities/MainActivity.kt`（重写，保留用户锁逻辑） |
| 摘除地区门禁 | `app/mobile/.../activities/MainActivity.kt`（重写，直接进主界面） |
| 删除封锁页 | `app/tv/.../screens/RegionBlockScreen.kt`、`app/mobile/.../screen/RegionBlockScreen.kt` |
| 删除检测代码 | `app/shared/.../util/NetworkUtil.kt` |
| 删除文案 | `strings.xml` 中 8 条 `region_block_*` |
| 更新声明 | `README.md` |

验证情况：全仓 grep 确认无 `NetworkUtil` / `RegionBlockScreen` / `region_block` 残留引用；本机无 JDK 与 Android SDK，未能执行 Gradle 编译，需在有构建环境的机器上跑 `./gradlew :app:tv:assembleDebug` 与 `:app:mobile:assembleDebug` 确认。

⚠️ **仍然成立的合规前提**（代码改动无法改变）：
1. 本 Demo **不得公开传播、分发或上架**，仅限本地开发调试；
2. 大陆正式运营前必须接入持牌方播控平台并与哔哩哔哩达成授权；
3. `BiliAppConf.kt` 仍使用 `android_hd` 非授权身份调用接口，接入持牌方时需替换为授权凭据。
