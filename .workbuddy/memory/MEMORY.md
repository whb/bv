# BV 项目长期记忆

## 项目身份
- 仓库：本地 B 站第三方客户端，fork 自 upstream `aaa1115910/bv`；用户自有仓库 `github.com/whb/bv`。品牌替换后所有 GitHub 仓库引用指向 `whb` owner（`whb/bv`、`whb/bv-libs`、`whb/bv-blacklist`）。
- **基础包名（applicationId / namespace 根）已改为 `dev.oaktech.bv`**（原为 `dev.aaa1115910.bv`），集中在 `buildSrc/src/main/kotlin/AppConfiguration.kt` 的 `appId` 定义；各模块 namespace 均引用它。
- 子模块包名：`dev.oaktech.bv`（app:mobile/tv/shared）、`dev.oaktech.biliapi`（bili-api）、`dev.oaktech.bilisubtitle`（bili-subtitle）、`dev.oaktech.symbols`（symbols）、`dev.oaktech.bv.player.*`（player/*）、`dev.oaktech.bv.utils`（utils）。
- 源码目录已同步：`src/.../dev/oaktech/...`。
- 品牌替换（2026-10-15 完成）：GitHub owner `aaa1115910` → `whb` 已全量替换——`AboutContent.kt`/`AboutSetting.kt` 项目主页、`GithubApi.kt` 的 `OWNER`（更新检查）、`VlcLibsApi.kt` 的 `whb/bv-libs`（VLC 库）、`AppConfiguration.kt` 的 `whb/bv-blacklist`（黑名单）、4 个 `.github/workflows/*.yml` 的 `github.repository == 'whb/bv'`、README/libs/README 链接。**有意保留**：`badge.versions.bv.aaa1115910.dev`（原作者徽章服务，无此域改了失效）、`© aaa1115910`（MIT 版权署名，法律要求保留）。
- ⚠️ 运行依赖：`whb/bv-libs`（VLC 原生库 release 检查）与 `whb/bv-blacklist`（黑名单数据）需用户自行建仓，否则 VLC 自动下载与黑名单功能在运行时失败。

## 构建与发布
- 编译：`JAVA_HOME` 指向 JDK21（`/d/devel/jdk-21.0.12.1+1`，Temurin；注意 `~/.workbuddy/binaries/build-tools/...` 这个路径**不存在**，别再用）。
- **打包架构逻辑（`app/build.gradle.kts`，2026-10-19 重写）**：
  - flavor `default` 含 VLC，`lite` 剔除 VLC（release + debug 都剔）；二者仅 VLC 这 3 个 `.so`（libvlc/libc++_shared/libvlcjni）不同。
  - 架构集合：非 release（debug 等）默认**全部 4 种**（x86_64/x86/arm64-v8a/armeabi-v7a）；`release` 默认仅 **arm64-v8a**；`release` 加 `-PdualAbi=true` 时为 **arm64-v8a + armeabi-v7a**。
  - 实现：`VLC` 剔除走 `androidComponents.onVariants` + `variant.packaging.jniLibs.excludes` 按 `variant.flavorName == "lite"`；架构走 `buildTypes.release.ndk.abiFilters`。**已移除**原先按 `gradle.startParameter.taskNames` 嗅探与 `splits` 块。
  - ⚠️ AGP 8.11 坑：variant API **没有** `variant.ndk.abiFilters`（仅 `externalNativeBuild.abiFilters`，是给 CMake 用的）；按变体限制 jniLibs 打包架构必须在 DSL 的 `buildTypes.xxx.ndk.abiFilters` 里设。
- symbols 模块 KSP（material-symbols-compose）从 `raw.githubusercontent.com` 拉图标 XML；本沙箱代理 3935 仅放行 github.com、拦截 raw 域，故 `:symbols:kspKotlin` 在此环境无法联网，不能本地重跑验证。已在 `symbols/build.gradle.kts` 配 `materialSymbols.network.cache.cacheDirectory` 持久缓存。
- 合规：Demo 仅本地验证，勿上架/分发；接持牌方须替换 `bili-api/.../BiliAppConf.kt` 的 `android_hd`。

## 已实现功能（截至 2026-10-15）
- 双架构 APK；弹幕全局开关（设置→播放设置→弹幕，默认关，移动端 BvPlayer 总开关已补）；"我的关注"up 主视频列表 WBI 签名根因修复 + "没有视频"空态；主菜单"分区"功能（ZoneGrid→ZoneDetail）。

## 已知坑与修复
- **Compose 1.8 + R8 + Android 9 闪退（已修复 2026-10-19）**：release 包在 Android 9 设备（雷鸟）draw 阶段崩 `IllegalStateException: LayoutNode should be attached to an owner`。根因：Compose 1.8 将 `ViewLayerContainer` 移至 `androidx.compose.ui.graphics.layer.view`，但 consumer proguard 规则仍 keep 旧包名，R8 删除 framework hidden API `dispatchGetDisplayList()` 的 override。修复：`app/proguard-rules.pro` 已加 keep 规则。若再遇类似"debug 稳 release 崩"的 Compose 内部 ISE，先反混淆（retrace + mapping）再对照 issuetracker。
