# 大车 Windows 桌面壳

.NET 8 + WinForms + WebView2 的单窗体壳，加载正式版网页 bundle，打出单个便携 EXE。

## 构建

需要 Windows + .NET 8 SDK（本仓库的 Linux 虚拟机上编不过，靠 CI 验证）。

```powershell
cd windows
powershell -ExecutionPolicy Bypass -File .\publish.ps1
```

等价的手动命令：

```powershell
dotnet publish .\Dache.csproj -c Release -r win-x64 --self-contained true /p:PublishSingleFile=true /p:IncludeNativeLibrariesForSelfExtract=true
```

产物：`bin\Release\net8.0-windows\win-x64\publish\Dache.exe`（单个文件，约 70–100MB，
含 .NET 运行时与约 24MB 网页 bundle）。CI 会把它改名成 `dache-0.1.0.exe` 再发 Release。

## 运行时依赖

- **WebView2 运行时（Evergreen）**：未装时启动会弹框说明并退出。
  随新版 Microsoft Edge / Windows 11 自带；没有的话去微软官网下载
  “Microsoft Edge WebView2 运行时”安装即可。本壳不自带运行时。
- 不需要安装 .NET 运行时（self-contained 已打进 EXE）。

## 数据位置

都在 `%LOCALAPPDATA%\Dache`：

| 位置 | 内容 |
| --- | --- |
| `%LOCALAPPDATA%\Dache\web-0.1.0\` | 内嵌 bundle 解压出来的网页（index.html + assets/）；版本标记 `version.txt` 内容不是 `0.1.0` 时清空重解 |
| `%LOCALAPPDATA%\Dache\WebView2\` | WebView2 用户数据（记录、localStorage 等），跨版本不变，覆盖安装 / 换 EXE 后还在 |

## 更新方式

- 启动 2 秒后自动在后台读一次
  `https://raw.githubusercontent.com/1binbinbin999-cloud/dache/main/version.json`（不缓存），
  按点分段比数字；有新版才弹框问要不要下载。
- 用户确认后从 GitHub Releases 下载 `dache-X.Y.Z.exe` 到 `Downloads` 文件夹，
  下载完提示用户去 Downloads 运行新 EXE 覆盖旧的。
- 读不到清单、无新版、下载失败时全程静默，不弹任何东西。
- 网页层自己的更新横幅在原生壳里会被压住（网页判断 `window.DacheWindows` 存在时不提示），
  更新只由原生壳负责，不会重复提示。

## 原生桥（给网页层）

`window.DacheWindows`（须 `await` 调用，宿主对象方法在 JS 里一律返回 Promise）：

- `await window.DacheWindows.GetAppVersion()` → `"0.1.0"`
- `await window.DacheWindows.CheckUpdate()` → 后台检查，有新版才弹框
- `await window.DacheWindows.OpenUrl(url)` → 系统默认浏览器打开（只接受 http/https）

实现细节：`AddHostObjectToScript` 实际挂在
`window.chrome.webview.hostObjects.DacheWindows` 下，壳在页面脚本执行前注入
`window.DacheWindows = chrome.webview.hostObjects.DacheWindows;` 做别名，
所以网页层按 `window.DacheWindows` 用即可。

## 发新版时要改的地方（两处，一起改）

1. `Dache.csproj` 的 `<Version>` / `<AssemblyVersion>` / `<FileVersion>`；
2. `Program.cs` 里 `AppInfo.Version`。

解压目录名（`web-X.Y.Z`）、版本标记、桥返回的版本号都从 `AppInfo.Version` 派生，不用别处再改。
