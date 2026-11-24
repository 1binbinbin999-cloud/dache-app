using System;
using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace Dache;

/// <summary>
/// 原生桥：通过 WebView2 的 AddHostObjectToScript 暴露给网页 JS。
/// JS 侧调用方式（宿主对象方法在 JS 里一律返回 Promise，须 await）：
///   await window.DacheWindows.GetAppVersion()   // -&gt; "0.1.0"
///   await window.DacheWindows.CheckUpdate()     // 后台检查，有新版才弹框
///   await window.DacheWindows.OpenUrl(url)      // 系统默认浏览器打开
/// 注意：COM 可见类必须标 [ComVisible(true)] 与 [ClassInterface(AutoDual)]，
/// 方法签名只用 string / void 这类 JS 能直接映射的简单类型。
/// </summary>
[ClassInterface(ClassInterfaceType.AutoDual)]
[ComVisible(true)]
public sealed class DacheWindowsBridge
{
    private const string VersionJsonUrl =
        "https://raw.githubusercontent.com/1binbinbin999-cloud/dache/main/version.json";
    private const string ReleaseDownloadUrlFormat =
        "https://github.com/1binbinbin999-cloud/dache/releases/download/v{0}/dache-{0}.exe";

    private static readonly HttpClient Http = new HttpClient
    {
        Timeout = TimeSpan.FromSeconds(15),
    };

    private readonly Form _form;

    static DacheWindowsBridge()
    {
        Http.DefaultRequestHeaders.UserAgent.ParseAdd("Dache/" + AppInfo.Version);
    }

    public DacheWindowsBridge(Form form)
    {
        _form = form ?? throw new ArgumentNullException(nameof(form));
    }

    public string GetAppVersion()
    {
        return AppInfo.Version;
    }

    /// <summary>只接受 http/https 绝对地址，用系统默认浏览器打开；不合法的地址静默忽略。</summary>
    public void OpenUrl(string url)
    {
        if (string.IsNullOrWhiteSpace(url))
        {
            return;
        }

        if (!Uri.TryCreate(url, UriKind.Absolute, out Uri? uri)
            || (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps))
        {
            return;
        }

        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = uri.AbsoluteUri,
                UseShellExecute = true,
            });
        }
        catch (Exception)
        {
            // 打不开默认浏览器也不向网页报错。
        }
    }

    /// <summary>
    /// 后台检查更新：无新版 / 读不到清单 / 下载失败时全程静默，不弹任何东西。
    /// 立即返回（JS 侧 await 到的只是“已发起检查”，不是检查结果）。
    /// </summary>
    public void CheckUpdate()
    {
        _ = Task.Run(CheckUpdateAsync);
    }

    private async Task CheckUpdateAsync()
    {
        try
        {
            string latest;
            string notes;
            using (HttpRequestMessage request = new HttpRequestMessage(HttpMethod.Get, VersionJsonUrl))
            {
                // 不缓存：每次都拉最新的清单。
                request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };
                using HttpResponseMessage response = await Http.SendAsync(request).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode)
                {
                    return;
                }

                using JsonDocument doc = JsonDocument.Parse(
                    await response.Content.ReadAsStringAsync().ConfigureAwait(false));
                JsonElement root = doc.RootElement;
                if (!root.TryGetProperty("version", out JsonElement versionElement)
                    || versionElement.ValueKind != JsonValueKind.String)
                {
                    return;
                }

                // 清单里的 version 可能是 "0.2.0" 或 "v0.2.0"，统一去掉 v 再比。
                latest = (versionElement.GetString() ?? string.Empty).Trim().TrimStart('v', 'V');
                notes = root.TryGetProperty("notes", out JsonElement notesElement)
                    && notesElement.ValueKind == JsonValueKind.String
                    ? notesElement.GetString() ?? string.Empty
                    : string.Empty;
            }

            if (string.IsNullOrEmpty(latest) || CompareVersions(latest, AppInfo.Version) <= 0)
            {
                return;
            }

            bool? agreed = InvokeOnUiThread(() =>
                MessageBox.Show(_form,
                    "发现新版本 v" + latest + "。\n\n" + notes + "\n\n是否现在下载新版？",
                    "大车 - 有新版本",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes);
            if (agreed != true)
            {
                return;
            }

            string downloads = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
            Directory.CreateDirectory(downloads);
            string fileName = "dache-" + latest + ".exe";
            string destPath = Path.Combine(downloads, fileName);
            string downloadUrl = string.Format(ReleaseDownloadUrlFormat, latest);

            using (HttpResponseMessage download = await Http.GetAsync(downloadUrl).ConfigureAwait(false))
            {
                if (!download.IsSuccessStatusCode)
                {
                    return;
                }

                using FileStream file = new FileStream(destPath, FileMode.Create, FileAccess.Write, FileShare.None);
                await download.Content.CopyToAsync(file).ConfigureAwait(false);
            }

            InvokeOnUiThread<object?>(() =>
            {
                MessageBox.Show(_form,
                    "新版已下载到：\n" + destPath +
                    "\n\n请关闭当前大车，运行新 EXE 覆盖旧的即可。\n记录保存在 %LOCALAPPDATA%\\Dache，不受影响。",
                    "大车 - 下载完成",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);
                return null;
            });
        }
        catch (Exception)
        {
            // 网络 / 解析 / 下载 / 弹框过程中的任何失败都静默，不打扰用户。
        }
    }

    /// <summary>按点分段比数字；版本号格式异常时返回 0（视为无新版）。</summary>
    private static int CompareVersions(string left, string right)
    {
        string[] leftParts = left.Split('.');
        string[] rightParts = right.Split('.');
        int count = Math.Max(leftParts.Length, rightParts.Length);
        for (int i = 0; i < count; i++)
        {
            int l = 0;
            int r = 0;
            if (i < leftParts.Length && !int.TryParse(leftParts[i], out l))
            {
                return 0;
            }
            if (i < rightParts.Length && !int.TryParse(rightParts[i], out r))
            {
                return 0;
            }
            if (l != r)
            {
                return l.CompareTo(r);
            }
        }
        return 0;
    }

    private T? InvokeOnUiThread<T>(Func<T> func)
    {
        try
        {
            if (_form.IsDisposed)
            {
                return default;
            }
            if (_form.InvokeRequired)
            {
                return (T?)_form.Invoke(func);
            }
            return func();
        }
        catch (ObjectDisposedException)
        {
            return default;
        }
        catch (InvalidOperationException)
        {
            return default;
        }
    }
}
