using System.IO;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

[InitializeOnLoad]
public static class VideoDiagnosticBuilder
{
    static VideoDiagnosticBuilder() { EditorApplication.update += Poll; }
    static void Poll()
    {
        const string request = "Temp/VideoDiagnosticBuild.request";
        if (!File.Exists(request) || EditorApplication.isCompiling || EditorApplication.isUpdating || BuildPipeline.isBuildingPlayer) return;
        File.Delete(request);
        var report = BuildPipeline.BuildPlayer(new BuildPlayerOptions {
            scenes = EditorBuildSettings.scenes.Where(s => s.enabled).Select(s => s.path).ToArray(),
            target = BuildTarget.Android,
            locationPathName = "pico-diagnostic.apk",
            options = BuildOptions.None
        });
        Debug.Log("VIDEO_DIAGNOSTIC_BUILD " + report.summary.result);
        File.WriteAllText("Temp/VideoDiagnosticBuild.result", report.summary.result.ToString());
    }
}
