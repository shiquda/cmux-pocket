# cmux Pocket

cmux Pocket 是一个独立的 Android 终端伴侣：在手机上连接 Mac 上正在运行的 cmux，并按 cmux 提供的结构化终端网格显示和操作工作区与标签页。

> 本项目是独立社区项目，不隶属于 cmux，也未获得 cmux 项目或其维护组织的背书。项目名称中的 “cmux” 仅用于说明兼容对象。

当前仓库提供源码构建，不提供预编译 APK。

## 功能

- 使用权威的 `cmux.render-grid.v1` 数据绘制终端，不在 Android 上运行本地 PTY/VT。
- 在手机上独立切换多个 Workspace 和 Tab，不跟随或改变 Mac 当前焦点。
- 同步 Mac 端标签页的新增和删除；关闭活动标签页前要求确认。
- 支持手机本地的主屏 scrollback；alternate screen/TUI 滚动仍由 Mac 端处理。
- 支持 USB 回环连接、用户保存的连接配置、局域网发现和用户提供的 `wss://` 端点；非回环连接必须使用 TLS。
- 内置紧凑型终端键盘，包含修饰键、导航键和 F1–F12。
- Gateway 要求随机认证令牌，默认只监听 `127.0.0.1`。

## 前置条件

- macOS 上已安装并运行 [cmux](https://cmux.com/)，且 `cmux ping` 成功。
- Python 3，以及用于隔离运行 Gateway 依赖的 [uv](https://docs.astral.sh/uv/)。
- Android Studio 或 Android SDK 35，并已通过 `ANDROID_HOME` 或未提交的 `android/local.properties` 配置 SDK 路径。
- JDK 17。
- 如需通过 USB 安装和连接：Android Platform Tools（`adb`）以及已开启 USB 调试的设备。

## 快速启动

### 1. 启动 Gateway

在仓库根目录执行：

```bash
mkdir -p "$HOME/.config/cmux-pocket"
umask 077
openssl rand -hex 32 > "$HOME/.config/cmux-pocket/gateway-token"
CMUX_AUTH_TOKEN_FILE="$HOME/.config/cmux-pocket/gateway-token" \
  uv run --with websockets python3 gateway/cmux_gateway.py
```

启动日志应同时出现：

```text
Live cmux detected! Using LiveCmuxBackend.
cmux WebSocket Gateway v2 listening on ws://127.0.0.1:8088
```

如果日志显示 `MockCmuxBackend`，Gateway 没有连接到真实 cmux；先确认 cmux 正在运行，并确认 `cmux ping` 成功。

### 2. 构建并安装 Android App

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 建立 USB 通道

```bash
adb reverse tcp:8088 tcp:8088
```

打开 cmux Pocket，选择内置的 `USB` 配置并填写：

- Host：`127.0.0.1`
- Port：`8088`
- Token：`$HOME/.config/cmux-pocket/gateway-token` 文件中的内容

连接成功后，App 会显示 cmux 当前可见的 Workspace 和 Tab。

## 网络与安全

- Gateway 强制只监听回环地址。远程接入必须通过本机 TLS 反向代理或安全隧道转发到该回环端点。
- 认证令牌必须从 `CMUX_AUTH_TOKEN` 或 `CMUX_AUTH_TOKEN_FILE` 提供。仓库不包含默认令牌，Gateway 也不会记录令牌。
- App 仅允许对 `127.0.0.1` 或 `localhost` 使用明文 WebSocket；所有非回环连接必须使用 `wss://`。本项目不附带域名、Tunnel ID 或云服务凭据。
- Android 使用 Android Keystore 支持的加密 SharedPreferences 保存连接配置和令牌，关闭应用数据备份，并在升级时迁移后删除旧的明文设置。仍请使用受信任且启用设备锁的手机。

安全问题请使用 GitHub 仓库的 **Security → Report a vulnerability** 私下报告，不要在公开 Issue 中提交令牌、地址、日志或终端内容。

## 开发与验证

运行 Android 单元测试和构建：

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

运行 Gateway 单元与集成测试：

```bash
cd gateway
uv run --with websockets python3 -m unittest test_gateway test_replay_normalize
```

运行 Android–Gateway 协议 E2E 测试：

```bash
uv run --with websockets python3 tests/e2e_android_gateway_test.py
```

测试使用仅绑定 `127.0.0.1` 的 mock Gateway 和固定测试令牌；这些测试值不是生产凭据。

## 项目结构

```text
android/    Android App（Kotlin、Jetpack Compose、OkHttp）
gateway/    Mac 端认证 WebSocket Gateway（Python）
tests/      Android–Gateway 协议 E2E 测试
```

开发过程中的研究记录、内部 Spec、机器配置、构建产物和凭据不属于公开仓库。

## 参与贡献

欢迎通过 Issue 报告可复现问题，或提交范围明确且附带验证结果的 Pull Request。请勿提交真实终端内容、认证令牌、私有主机地址或其他个人数据。

## 许可证

除另有说明外，项目代码以 [GNU Affero General Public License v3.0](LICENSE) 发布。

随 App 分发的 Maple Mono NF 字体遵循 [SIL Open Font License 1.1](LICENSES/MapleMono-OFL-1.1.txt)。详情见 [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES)。
