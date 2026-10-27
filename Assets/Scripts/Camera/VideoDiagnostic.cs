using System;
using System.IO;
using UnityEngine;
using Robot.Conf;

// Inert unless explicitly enabled by an ADB-written file in app external storage.
public class VideoDiagnostic : MonoBehaviour
{
    public static string Mode = "off";
    static bool painted;
    float nextPoll;
    string lastCommand;
    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    static void Install() { DontDestroyOnLoad(new GameObject("VideoDiagnostic").AddComponent<VideoDiagnostic>().gameObject); }
    void Update()
    {
        if (Application.platform != RuntimePlatform.Android || Time.realtimeSinceStartup < nextPoll) return;
        nextPoll = Time.realtimeSinceStartup + 1;
        string path = Path.Combine(Application.persistentDataPath, "video-diagnostic.txt");
        if (!File.Exists(path)) return;
        string command = File.ReadAllText(path).Trim();
        if (command == lastCommand) return;
        var parts = command.Split(' ');
        if (parts.Length < 1 || !(parts[0] == "uv" || parts[0] == "cpu" || parts[0] == "fbo" || parts[0] == "video" || parts[0] == "off")) return;
        var ctrl = FindObjectOfType<UICameraCtrl>();
        var manager = FindObjectOfType<VideoSourceManager>();
        if (ctrl == null || manager == null) return;
        lastCommand = command;
        Mode = parts[0];
        painted = false;
        using (var java = new AndroidJavaClass("com.picoxr.fbolibrary.FBOPlugin"))
            java.CallStatic("configureDiagnostic", Mode, Path.Combine(Application.persistentDataPath, "diagnostic-" + Mode + ".png"));
        if (Mode == "off") { ctrl.OnListenCameraBtn(false); return; }
        manager.UpdateVideoSource("TOROBO_CAMERA");
        ctrl.listenBtn.SetOn(true);
        if (!FindObjectOfType<RemoteCameraWindow>() || !FindObjectOfType<RemoteCameraWindow>().IsListening)
            ctrl.RequestCameraStream(parts.Length > 1 ? parts[1] : "192.168.88.173");
        Debug.Log("VIDEO_DIAGNOSTIC mode=" + Mode + " build=20261001-a");
    }
    public static bool PaintCpu(Texture2D texture)
    {
        if (Mode != "cpu") return false;
        if (!painted)
        {
            var colors = new Color32[texture.width * texture.height];
            Color32[] bars = { new Color32(255,0,0,255), new Color32(0,255,0,255), new Color32(0,0,255,255) };
            for (int y=0; y<texture.height; y++) for (int x=0; x<texture.width; x++)
                colors[y*texture.width+x] = bars[(x % (texture.width/2)) * 3 / (texture.width/2)];
            texture.SetPixels32(colors);
            texture.Apply(false, false);
            painted = true;
            Debug.Log("VIDEO_DIAGNOSTIC cpu painted texture=" + texture.GetNativeTexturePtr());
        }
        return true;
    }
}
