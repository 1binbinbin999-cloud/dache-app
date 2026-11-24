# 大车 Android 壳 v0.1.0

原生壳（Kotlin + WebView），加载正式版网页 bundle。不含任何网页逻辑：
密码门与同步都在网页层（`../web/`），壳只提供加密存储、版本/更新、外部链接。

## 结构

```
android/
├── gradlew / gradlew.bat / gradle/wrapper/   # Gradle 8.5 wrapper（CI 不用另装 Gradle）
├── settings.gradle.kts / build.gradle.kts / gradle.properties
└── app/
    ├── build.gradle.kts                      # 包名/版本/依赖/签名/syncWebAssets 任务
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/dache/app/MainActivity.kt
        └── res/  (values、xml/file_paths、自适应图标 + 各密度 PNG 占位图标)
```

构建时 Gradle 任务 `syncWebAssets` 会把 `../web/` 的全部内容同步进
`app/src/main/assets/`（`index.html` 在 assets 根目录，`assets/` 子目录原样保留），
APK 里的 bundle 与仓库里的 `web/` 一致。`app/src/main/assets/` 已加入
`.gitignore`，不入库（约 24MB，每次构建重新生成）。

## 构建

```sh
./gradlew assembleDebug        # 调试包（默认 debug 签名）
./gradlew assembleRelease      # 正式包：必须配齐四个签名环境变量，缺一即报错退出
./gradlew bundleRelease        # AAB 同理
```

需要 JDK 17（AGP 8.2.2 要求）。CI 例：`actions/setup-java@v4` 选 `java-version: '17'`。

## 签名（release 专用）

四个环境变量，全部来自 CI Secrets，绝不写进代码或仓库：

| 变量 | 说明 |
| --- | --- |
| `DACHE_KEYSTORE_FILE` | keystore 文件路径（CI 从 Secrets 还原到临时路径后传进来；相对路径以 `app/` 目录为准） |
| `DACHE_KEYSTORE_PASSWORD` | keystore 密码 |
| `DACHE_KEY_ALIAS` | key 别名 |
| `DACHE_KEY_PASSWORD` | key 密码 |

只有当本次调用确实要打 release 包（任务名含 `Release`，或 `build`/`assemble`/`bundle`）
时才检查；缺任何一个，配置阶段即抛 `GradleException` 明确报错退出，
不会悄悄打出未签名包。跑 debug / tasks / lint 不受影响。

## 运行时行为

- WebView：JS + DOM Storage 开；`WebViewAssetLoader` 把 `assets/` 根目录映射到
  `https://appassets.androidplatform.net/`，加载该域名的 `index.html`；
  站内链接留在 WebView，站外 http/https 交系统浏览器；`file://` 访问按官方示例关掉。
- `window.DacheSecure`：`getItem(key): String?` / `setItem(key, value)` /
  `removeItem(key)`，同步透传；底层 `EncryptedSharedPreferences`
 （MasterKey AES256_GCM，键 AES256_SIV、值 AES256_GCM），偏好文件 `dache_secure_prefs`。
  键名由网页层定（`dache.vault`、`dache.token`），壳不解析。
  加密文件不参与自动备份（`allowBackup=false`），否则恢复后密钥不在、vault 变砖。
- `window.DacheNative`：`getAppVersion()` 返回 `BuildConfig.VERSION_NAME`；
  `checkUpdate()` 触发更新检查；`openUrl(url)` 用系统浏览器打开。
- 更新：启动 2 秒后自动检查一次（后台线程）。读
  `https://raw.githubusercontent.com/1binbinbin999-cloud/dache/main/version.json`
  （`Cache-Control: no-store`），取 `version` 与 `notes` 字段；按点分段比数字，
  有新版才在主线程弹 `AlertDialog`（标题“有新版 vX.Y.Z”，内容为 `notes`，
  按钮“更新”/“稍后”）。点“更新”后下载
  `https://github.com/1binbinbin999-cloud/dache/releases/download/vX.Y.Z/dache-X.Y.Z.apk`
  到 `cacheDir/dache-update/`，经 `FileProvider`（`${applicationId}.fileprovider`，
  只允许 `cache-path/dache-update/`）拉起系统安装。读不到 version.json 或下载失败时
  静默，不影响使用。需 `REQUEST_INSTALL_PACKAGES` 权限；Android 8+ 另需用户允许
  本应用“安装未知应用”，安装时系统会引导。
- 返回键：WebView 能后退就后退，否则退出。

## 尚未验证

本机无 Java 与 Android SDK，本地编不过、跑不了真机。以上代码按官方文档写法逐行核对过，
须由 CI（`./gradlew assembleRelease`，配齐签名 Secrets）验证编译与打包；
真机验收（密码门存取、更新弹框、覆盖安装）须在拿到 APK 后实测。

## 图标

当前启动图标为占位（深色底 + 金环 + 金点，与网页版玄夜风格一致）；
正式图标定稿后替换 `res/drawable/ic_launcher_foreground.xml`
与各 `mipmap-*/ic_launcher*.png` 即可。
