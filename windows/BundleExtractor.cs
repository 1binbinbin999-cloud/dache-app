using System;
using System.IO;
using System.Linq;
using System.Reflection;

namespace Dache;

/// <summary>
/// 把程序集里内嵌的网页 bundle（资源名以 "web." 开头，见 Dache.csproj 的 LogicalName）
/// 解压到 %LOCALAPPDATA%\Dache\web-&lt;版本&gt;\，按资源名还原目录结构。
/// 版本标记文件内容与当前版本不一致时清空重解；一致且 index.html 存在时直接复用。
/// </summary>
internal static class BundleExtractor
{
    private const string ResourcePrefix = "web.";
    private const string MarkerFileName = "version.txt";
    private const string IndexFileName = "index.html";

    public static string Extract()
    {
        string webDir = Path.Combine(AppPaths.DataRoot, "web-" + AppInfo.Version);
        string markerPath = Path.Combine(webDir, MarkerFileName);

        if (IsCurrent(webDir, markerPath))
        {
            return webDir;
        }

        if (Directory.Exists(webDir))
        {
            Directory.Delete(webDir, recursive: true);
        }
        Directory.CreateDirectory(webDir);

        Assembly assembly = typeof(BundleExtractor).Assembly;
        string[] names = assembly.GetManifestResourceNames()
            .Where(n => n.StartsWith(ResourcePrefix, StringComparison.Ordinal))
            .OrderBy(n => n, StringComparer.Ordinal)
            .ToArray();

        if (names.Length == 0)
        {
            throw new InvalidOperationException(
                "程序集里没有找到以 \"web.\" 开头的内嵌网页资源，" +
                "构建时可能没有把 ../web/ 嵌进来（检查 Dache.csproj 的 EmbeddedResource 一项）。");
        }

        foreach (string name in names)
        {
            // 资源名示例："web.assets\ni\ni-1-qian.jpg"（或构建机用 '/' 分隔），
            // 去掉前缀后就是 bundle 内的相对路径。
            string relative = name.Substring(ResourcePrefix.Length);
            string[] parts = relative.Split(
                new[] { '\\', '/' }, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0)
            {
                continue;
            }

            string dest = Path.GetFullPath(
                Path.Combine(new[] { webDir }.Concat(parts).ToArray()));

            // 防御：解压目标必须落在 webDir 里面。
            if (!dest.StartsWith(webDir + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
                && !string.Equals(dest, webDir, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException("内嵌资源路径异常，拒绝解压：" + name);
            }

            string? dir = Path.GetDirectoryName(dest);
            if (!string.IsNullOrEmpty(dir))
            {
                Directory.CreateDirectory(dir);
            }

            using Stream source = assembly.GetManifestResourceStream(name)
                ?? throw new InvalidOperationException("读不到内嵌资源：" + name);
            using FileStream target = new FileStream(dest, FileMode.Create, FileAccess.Write, FileShare.None);
            source.CopyTo(target);
        }

        // 写版本标记：下次启动版本一致就不再重解。
        File.WriteAllText(markerPath, AppInfo.Version);

        // 顺手清掉旧版本留下的 web-* 目录（尽力而为，失败不管）。
        try
        {
            foreach (string oldDir in Directory.GetDirectories(AppPaths.DataRoot, "web-*"))
            {
                if (!string.Equals(oldDir, webDir, StringComparison.OrdinalIgnoreCase))
                {
                    Directory.Delete(oldDir, recursive: true);
                }
            }
        }
        catch (Exception)
        {
            // 旧目录清不掉不影响运行，不管。
        }

        return webDir;
    }

    private static bool IsCurrent(string webDir, string markerPath)
    {
        try
        {
            return File.Exists(Path.Combine(webDir, IndexFileName))
                && File.Exists(markerPath)
                && File.ReadAllText(markerPath).Trim() == AppInfo.Version;
        }
        catch (IOException)
        {
            return false;
        }
        catch (UnauthorizedAccessException)
        {
            return false;
        }
    }
}
