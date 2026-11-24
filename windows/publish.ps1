# 大车 Windows 壳发布脚本
# 在 Windows + .NET 8 SDK 环境下运行（CI 用 windows-latest），在 windows/ 目录下执行：
#   powershell -ExecutionPolicy Bypass -File .\publish.ps1
# 产物：bin\Release\net8.0-windows\win-x64\publish\Dache.exe（单个文件，约 70-100MB，
# 含 .NET 运行时与约 24MB 网页 bundle）。CI 会把它改名成 dache-0.1.0.exe 再发 Release。

$ErrorActionPreference = "Stop"

dotnet publish "$PSScriptRoot\Dache.csproj" `
    -c Release `
    -r win-x64 `
    --self-contained true `
    /p:PublishSingleFile=true `
    /p:IncludeNativeLibrariesForSelfExtract=true

Write-Host "发布完成：$PSScriptRoot\bin\Release\net8.0-windows\win-x64\publish\Dache.exe"
