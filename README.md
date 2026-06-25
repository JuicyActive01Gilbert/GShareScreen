# G享屏

G享屏是一个 Android 到 Windows PC 的投屏项目。Android 端负责采集屏幕和系统播放声音，PC 端负责配对、接收、播放和窗口显示控制。

当前项目只支持 Android，不支持 iOS。

## 功能

- Android 屏幕投屏到 Windows PC
- 支持系统播放声音采集和 PC 端音量调节
- 支持局域网 WebRTC 投屏
- 支持 USB 数据线直连投屏，依赖 ADB reverse
- 支持连接码配对
- 支持画质和 FPS 分开设置
- 支持手机横竖屏切换后的 PC 端自适应显示
- 支持 PC 端窗口全屏和系统全屏
- Android 开始共享后可进入悬浮窗控制

## 项目结构

```text
.
├─ android-app/          Android Kotlin 投屏端
├─ pc-client/            Windows Electron 接收端
├─ scripts/              本地开发、安装和打包脚本
└─ release/              本地发布产物，已被 .gitignore 排除
```

## 技术栈

- Android：Kotlin、Android MediaProjection、WebRTC、OkHttp WebSocket
- PC：Electron、WebSocket、WebRTC Renderer、ADB reverse
- 构建：Gradle Wrapper、npm

## 环境要求

- Windows 10/11
- Node.js 和 npm
- JDK 21
- Android SDK，建议安装 Android SDK Platform 35、Build Tools、Platform Tools
- Android 10+ 真机
- USB 直连需要手机开启开发者选项和 USB 调试

如果网络访问 Gradle、Maven 或 npm 较慢，可以使用国内镜像。Android 项目的 `settings.gradle.kts` 已配置阿里云 Maven 镜像；npm 可自行配置：

```powershell
npm config set registry https://registry.npmmirror.com/
npm config set electron_mirror https://npmmirror.com/mirrors/electron/
```

## 运行 PC 端

```powershell
.\scripts\start-pc.ps1
```

也可以手动运行：

```powershell
cd pc-client
npm install
npm start
```

PC 端启动后会显示连接码。Android 端输入这个连接码即可配对。

## 构建和安装 Android 端

构建 debug APK：

```powershell
.\scripts\build-android.ps1
```

安装到已连接的 Android 真机：

```powershell
.\scripts\install-android.ps1
```

手动构建：

```powershell
cd android-app
.\gradlew.bat :app:assembleDebug
```

debug APK 输出位置：

```text
android-app\app\build\outputs\apk\debug\app-debug.apk
```

## 使用方式

1. 启动 PC 端。
2. Android 手机和 PC 在同一局域网，或使用 USB 数据线连接并开启 USB 调试。
3. 在 Android 端输入 PC 端显示的连接码。
4. 选择画质和 FPS。
5. 点击开始共享，并授予录屏、声音采集和悬浮窗权限。

首次运行时 Windows 防火墙可能会询问网络访问权限，需要允许 PC 端监听局域网连接。

## 发布打包

构建 Android release：

```powershell
cd android-app
.\gradlew.bat :app:assembleRelease
```

生成位置：

```text
android-app\app\build\outputs\apk\release\app-release.apk
```

打包 PC 便携版：

```powershell
.\scripts\package-pc.ps1
```

生成位置：

```text
release\GShareScreen-PC-<version>-win-x64-portable.zip
```

## Android release 签名

真实签名文件和密码不应提交到 GitHub。仓库只保留示例文件：

```text
android-app\keystore.properties.example
```

本地打 release 包时，复制一份：

```powershell
Copy-Item .\android-app\keystore.properties.example .\android-app\keystore.properties
```

然后按实际情况填写：

```properties
storeFile=keystore/gshare-release.jks
storePassword=your-store-password
keyAlias=gshare
keyPassword=your-key-password
```

也可以用环境变量：

```text
GSHARE_RELEASE_STORE_FILE
GSHARE_RELEASE_STORE_PASSWORD
GSHARE_RELEASE_KEY_ALIAS
GSHARE_RELEASE_KEY_PASSWORD
```

可用 `keytool` 生成本地签名文件：

```powershell
keytool -genkeypair -v -keystore android-app\keystore\gshare-release.jks -alias gshare -keyalg RSA -keysize 2048 -validity 10000
```

请妥善保存 release keystore。Android 同包名应用后续升级必须使用同一个 keystore 签名。

## 端口

- `3765`：PC 端 WebSocket 信令端口，端口占用时会尝试递增
- `3766`：UDP 局域网发现端口
- `3767`：USB 直连 WebSocket 端口

## 协议

本项目采用 [PolyForm Noncommercial License 1.0.0](LICENSE)。

- 学习、研究、个人使用等非商业用途可以自由使用、修改和分发。
- 商业用途不在该协议授权范围内，需要提前联系原作者获得单独授权。
- 分发时需要保留 [NOTICE](NOTICE) 中的 `Required Notice`，注明项目来源。
- 第三方依赖仍受其各自许可证约束。

## 已知限制

- Android 10+ 才支持系统播放声音采集。
- 部分 App 会禁止音频采集。
- DRM、受保护视频或系统限制内容可能无法被正常投屏。
- USB 直连依赖 ADB，PC 端需要能找到 `adb`。
- 当前 PC 发布包是 Windows 便携版，不包含安装器和代码签名。
