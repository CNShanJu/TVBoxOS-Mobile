# TVBoxMobile (MBox)

基于 [q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS) 的 TVBox 点播/直播应用:多源订阅、JS 爬虫、内置下载、HLS 合并、字幕、局域网遥控。

> 精力有限,未必会及时维护,仅用于学习。

## 功能特性

- **多源订阅**:远程 JSON 配置,兼容 BOM/注释/图片+base64/AES 等非标准套路(见 `doc/接口解析.md`)
- **点播 / 直播 / 快捷搜索**:多线路选集、EPG、倍速、字幕、投屏桩
- **内置下载**:并发调度(1-5)、断点续传、m3u8 分段下载合并 mp4、磁盘空间预检、断网自动续传、来源/剧名分目录
- **自适应卡片**:剧集卡片统一 宽:高 = 3:4,列数随屏幕宽度自适应(单卡 ≤190dp),大屏旋转自动刷新
- **主题**:浅色/深色两套,构建期由 JSON 生成颜色资源,界面配色统一走 `@color/*`
- **崩溃兜底 + 运行日志**:崩溃页与 logcat 完整捕获查看

## 构建环境(核心版本)

| 项 | 版本 |
|---|---|
| **JDK** | **17**(AGP 8.x 必需) |
| **Gradle** | **8.4**(Wrapper 自带,见 `gradle/wrapper/gradle-wrapper.properties`) |
| **Android Gradle Plugin** | **8.2.2** |
| **Kotlin** | **1.9.22** |
| **compileSdk / targetSdk** | **34** |
| **minSdk** | **24** |
| Java 兼容级别 | 1.8(source/target) |
| 关键依赖 | AndroidX、Material 1.9.0、OkHttp 4.12.0、ExoPlayer 2.18.7、Picasso、QuickJS |

> `local.properties`(本机 SDK 路径)不入库,首次构建需用 Android Studio 打开或自行配置。

## 本地构建

Windows:

```bat
set JAVA_HOME=D:\path\to\jdk17
gradlew.bat assembleRelease
```

macOS / Linux:

```bash
export JAVA_HOME=/path/to/jdk17
./gradlew assembleRelease
```

产物路径:`app/build/outputs/apk/release/`(如 `MBox_v2.2.0_release_20260823.apk`)。
应用名/版本号/图标统一在 `app/app_config.properties` 维护,改完重新构建即可。

## GitHub Actions 打包

仓库内置工作流 [.github/workflows/build-apk.yml](.github/workflows/build-apk.yml),在 GitHub 上即可出包:

1. 推送代码到 GitHub(main 分支或 `v*` 标签自动触发;也可进 **Actions → Build APK → Run workflow** 手动触发)
2. 构建完成后在本次运行的 **Artifacts** 区下载 APK

签名说明:`TVBoxOSC.jks` 已随仓库提交,CI 与本地使用同一正式签名,产物可覆盖升级本地旧版本。
(提醒:签名库与密码公开在仓库内,任何能访问仓库的人都能用该正式签名发包。)

## 目录结构与文档

- `doc/` — 项目文档:
  - [项目目录结构.md](doc/项目目录结构.md) — 模块/包/关键类总览
  - [项目状态速查.md](doc/项目状态速查.md) — 版本、主题、组件、功能、已知注意速查
  - [接口解析.md](doc/接口解析.md) — 订阅解析机制与排查经验
  - [直播说明.md](doc/直播说明.md) — 直播源说明(基于 [vbskycn/iptv](https://github.com/vbskycn/iptv))
  - [Git提交规范.md](doc/Git提交规范.md) — 提交信息格式要求

## 致谢

- 上游:[q215613905/TVBoxOS](https://github.com/q215613905/TVBoxOS)
- 推荐使用:[takagen99/Box](https://github.com/takagen99/Box)、[FongMi/TV](https://github.com/FongMi/TV)

## License

见 [LICENSE](LICENSE)
