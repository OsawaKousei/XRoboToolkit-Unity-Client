using System;
using System.Text.RegularExpressions;

public static class ToroboVideoProfileMigration
{
    public const string ProfileName = "TOROBO_CAMERA";

    private const string ProfileYaml = @"
- name: ""TOROBO_CAMERA""
  camera: ""ROS_CAMERA""
  description: ""Torobo SR300 mono camera (duplicated side-by-side)""
  properties:
    - name: ""visibleRatio""
      type: ""float""
      value: 0.555
    - name: ""contentRatio""
      type: ""float""
      value: 1.8
    - name: ""heightCompressionFactor""
      type: ""float""
      value: 1.333333
    - name: ""RawImageRectSize""
      type: ""string""
      value: ""600x225""
    - name: ""CamWidth""
      type: ""int""
      value: 1280
    - name: ""CamHeight""
      type: ""int""
      value: 480
    - name: ""CamFPS""
      type: ""int""
      value: 30
    - name: ""CamBitrate""
      type: ""int""
      value: 4000000
";

    public static bool TryAppend(string yaml, out string migratedYaml)
    {
        migratedYaml = yaml;
        if (string.IsNullOrWhiteSpace(yaml) || ContainsProfile(yaml))
        {
            return false;
        }

        string newline = yaml.Contains("\r\n") ? "\r\n" : "\n";
        string normalizedProfile = ProfileYaml.TrimStart('\r', '\n').Replace("\n", newline);
        migratedYaml = yaml.TrimEnd('\r', '\n') + newline + newline + normalizedProfile;
        return true;
    }

    private static bool ContainsProfile(string yaml)
    {
        return Regex.IsMatch(
            yaml,
            @"(?im)^-\s+name:\s*['""']?TOROBO_CAMERA['""']?\s*(?:#.*)?$",
            RegexOptions.CultureInvariant);
    }
}
