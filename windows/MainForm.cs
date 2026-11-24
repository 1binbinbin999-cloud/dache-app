using System;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace Dache;

/// <summary>
/// 大车 Windows 桌面壳：单窗体 + WebView2，加载内嵌的正式版网页 bundle。
/// 窗体关闭即正常退出（主窗体关闭后 Application.Run 自然返回）。
/// </summary>
public sealed class MainForm : Form
{
    private readonly WebView2 _webView;
    private DacheWindowsBridge? _bridge;

    public MainForm()
    {
        Text = "大车";
        Size = new Size(1080, 720);
        StartPosition = FormStartPosition.CenterScreen;

        _webView = new WebView2 { Dock = DockStyle.Fill };
        Controls.Add(_webView);

        Load += MainForm_Load;
    }

    private async void MainForm_Load(object? sender, EventArgs e)
    {
        // 1. 内嵌 bundle 解压到 %LOCALAPPDATA%\Dache\web-0.1.0\（版本不对就清空重解）。
        string webDir;
        try
        {
            webDir = BundleExtractor.Extract();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this,
                "解压内置网页文件失败，应用无法启动。\n\n" + ex.Message,
                "大车", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Application.Exit();
            return;
        }

        // 2. 初始化 WebView2。UserDataFolder 固定在 %LOCALAPPDATA%\Dache\WebView2\，
        //    跨版本不变，记录与 localStorage 在覆盖安装 / 换 EXE 后还在。
        //    browserExecutableFolder 传 null：用本机已安装的 Evergreen WebView2 运行时。
        CoreWebView2 core;
        try
        {
            string userDataFolder = Path.Combine(AppPaths.DataRoot, "WebView2");
            CoreWebView2Environment env = await CoreWebView2Environment.CreateAsync(
                browserExecutableFolder: null,
                userDataFolder: userDataFolder);
            await _webView.EnsureCoreWebView2Async(env);
            core = _webView.CoreWebView2
                ?? throw new InvalidOperationException("WebView2 初始化未完成。");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this,
                "无法启动 WebView2，可能是本机没有安装 WebView2 运行时。\n" +
                "请先安装 Microsoft Edge WebView2 运行时（随新版 Edge / Windows 11 自带），再重新打开大车。\n\n" +
                "错误信息：" + ex.Message,
                "大车", MessageBoxButtons.OK, MessageBoxIcon.Error);
            Application.Exit();
            return;
        }

        // 3. 注册原生桥。AddHostObjectToScript 实际挂在
        //    window.chrome.webview.hostObjects.DacheWindows 下；
        //    再用文档创建前注入的脚本把它别名到 window.DacheWindows，
        //    这样网页层现有的 window.DacheWindows 判断与 await 调用都能直接用
        //    （注入脚本在页面任何脚本执行前运行，必须在 Navigate 之前 await 完成）。
        _bridge = new DacheWindowsBridge(this);
        core.AddHostObjectToScript("DacheWindows", _bridge);
        await core.AddScriptToExecuteOnDocumentCreatedAsync(
            "window.DacheWindows = chrome.webview.hostObjects.DacheWindows;");

        // 4. 虚拟主机映射：https://appassets/ 指向解压目录，拒绝跨源访问。
        core.SetVirtualHostNameToFolderMapping(
            "appassets", webDir, CoreWebView2HostResourceAccessKind.DenyCors);
        core.Navigate("https://appassets/index.html");

        // 5. 启动 2 秒后在后台跑一次更新检查（无新版 / 读不到清单 / 下载失败时不弹任何东西）。
        DacheWindowsBridge bridge = _bridge
            ?? throw new InvalidOperationException("原生桥未初始化。");
        _ = Task.Run(async () =>
        {
            await Task.Delay(2000);
            bridge.CheckUpdate();
        });
    }
}
