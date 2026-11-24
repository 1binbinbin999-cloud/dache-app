using System;
using System.IO;
using System.Windows.Forms;

namespace Dache;

internal static class AppInfo
{
    /// <summary>应用版本。发新版时与 Dache.csproj 的 &lt;Version&gt; 一起改。</summary>
    public const string Version = "0.1.0";
}

internal static class AppPaths
{
    /// <summary>%LOCALAPPDATA%\Dache：解压的网页、WebView2 用户数据都放这里。</summary>
    public static string DataRoot => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Dache");
}

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
    }
}
