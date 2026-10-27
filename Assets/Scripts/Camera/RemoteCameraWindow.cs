using System.Collections;
using UnityEngine;
using LitJson;
using Network;
using Robot;
using UnityEngine.UI;


/// <summary>
/// Display window of PC camera
/// Responsible for receiving, decoding, and displaying data
/// </summary>
public class RemoteCameraWindow : MonoBehaviour
{
    public RawImage RemoteCameraImage;
    private Texture2D _texture;
    public Texture2D Texture => _texture;
    private Coroutine _startListenCoroutine;
    private bool _listening;
    private bool _decoderReady;
    private int _textureFailures;
    private float _nextHealthRealtime;
    private int _decoderPolls;
    private int _textureUpdates;
    private int _hitchFrames;
    private double _decoderPollTotalMs;
    private double _decoderPollMaxMs;
    private float _frameDeltaMaxMs;

    private const float HealthIntervalSeconds = 5f;
    private const float HitchThresholdMs = 25f;

    public bool IsListening => _listening;

    private int _resolutionWidth = 2160;
    private int _resolutionHeight = 2160 / 2 * 4 / 3;
    private int _videoFps = 60;
    private int _bitrate = 40 * 1024 * 1024;

    public CustomButton listenBtn;

    private void Awake()
    {
        transform.position = Camera.main.transform.position;
        transform.rotation = Camera.main.transform.rotation;
    }

    public void StartListen(int width, int height, int fps, int bitrate, int port)
    {
        if (_listening)
        {
            return;
        }

        _listening = true;
        _resolutionWidth = width;
        _resolutionHeight = height;
        _videoFps = fps;
        _bitrate = bitrate;
        ResetPerformanceWindow();

        _startListenCoroutine = StartCoroutine(OnStartListen(port));
    }

    private void OnDisable()
    {
        StopListen();
        Debug.Log("RemoteCameraWindow OnDisable");
        TcpHandler.SendFunctionValue("StopReceivePcCamera", "");
    }

    public void StopListen()
    {
        _decoderReady = false;
        if (_startListenCoroutine != null)
        {
            StopCoroutine(_startListenCoroutine);
            _startListenCoroutine = null;
        }

        if (_listening)
        {
            // The Android implementation closes both the listening ServerSocket
            // and an accepted Socket before joining its receive thread. This also
            // covers Listen-off while no video sender has connected yet.
            try { MediaDecoder.release(); }
            catch (System.Exception error) { Debug.LogException(error); }
            finally { GL.InvalidateState(); }
            _listening = false;
        }

        if (_texture != null)
        {
            if (RemoteCameraImage != null)
            {
                RemoteCameraImage.texture = null;
            }
            Destroy(_texture);
            _texture = null;
        }
    }

    public void OnCloseBtn()
    {
        // Notify the owner instead of changing only the visual toggle. The
        // Listen-off callback sends CLOSE_CAMERA, revokes audio, and disconnects
        // the managed control socket before this window is hidden.
        listenBtn.SetOnAndNotify(false);
        gameObject.SetActive(false);
    }

    public IEnumerator OnStartListen(int port)
    {
        Debug.Log("StartListen port:" + port);

        // The Android decoder renders into this Unity texture through an OpenGL
        // framebuffer. Use RGBA32 and allocate storage explicitly while investigating
        // GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT; RGB24 alone was not proven causal.
        _texture = new Texture2D(_resolutionWidth, _resolutionHeight, TextureFormat.RGBA32, false, false)
        {
            filterMode = FilterMode.Bilinear,
            wrapMode = TextureWrapMode.Clamp
        };
        // Allocate/upload the native GL texture before passing its ID to Java.
        _texture.Apply(false, false);
        RemoteCameraImage.texture = _texture;
        yield return null;

        bool initialized = false;
        try
        {
            MediaDecoder.initialize((int)_texture.GetNativeTexturePtr(), _resolutionWidth, _resolutionHeight);
            MediaDecoder.startServer(port, false);
            _decoderReady = initialized = true;
        }
        catch (System.Exception error) { Debug.LogException(error); }
        finally { GL.InvalidateState(); }
        if (!initialized)
        {
            _startListenCoroutine = null;
            StopListen();
            yield break;
        }
        yield return null;

        JsonData cameraParam = new JsonData();
        cameraParam["ip"] = Utils.GetLocalIPv4();
        cameraParam["port"] = port;
        cameraParam["width"] = _resolutionWidth;
        cameraParam["height"] = _resolutionHeight;
        cameraParam["fps"] = _videoFps;
        cameraParam["bitrate"] = _bitrate;
        TcpHandler.SendFunctionValue("StartReceivePcCamera", cameraParam.ToJson());
        _startListenCoroutine = null;
    }

    private void LateUpdate()
    {
        //Keep the window facing the camera at all times
        if (Camera.main != null)
        {
            transform.position = Camera.main.transform.position;
            transform.rotation = Camera.main.transform.rotation;
        }
    }

    private void Update()
    {
        if (!_decoderReady || _texture == null || Application.platform != RuntimePlatform.Android)
        {
            return;
        }

        float now = Time.realtimeSinceStartup;
        if (VideoDiagnostic.PaintCpu(_texture)) return;
        float frameDeltaMs = Time.unscaledDeltaTime * 1000f;
        _frameDeltaMaxMs = Mathf.Max(_frameDeltaMaxMs, frameDeltaMs);
        if (frameDeltaMs >= HitchThresholdMs)
        {
            _hitchFrames++;
        }

        // Match the reference: poll in Update, copy only a newly available frame.
        long started = System.Diagnostics.Stopwatch.GetTimestamp();
        try
        {
            bool hasFrame = MediaDecoder.isUpdateFrame();
            if (hasFrame)
            {
                MediaDecoder.updateTexture();
                _textureUpdates++;
            }
        }
        catch (System.Exception error)
        {
            // A failed FBO copy must not count as a displayed frame.
            if (_textureFailures++ == 0) Debug.LogException(error);
        }
        finally
        {
            GL.InvalidateState();
            double elapsedMs =
                (System.Diagnostics.Stopwatch.GetTimestamp() - started) * 1000.0 /
                System.Diagnostics.Stopwatch.Frequency;
            _decoderPolls++;
            _decoderPollTotalMs += elapsedMs;
            _decoderPollMaxMs = System.Math.Max(_decoderPollMaxMs, elapsedMs);
        }

        if (now >= _nextHealthRealtime)
        {
            float windowSeconds = HealthIntervalSeconds;
            double averagePollMs = _decoderPolls > 0
                ? _decoderPollTotalMs / _decoderPolls
                : 0.0;
            CrashProbe.Breadcrumb(
                "remote_video.health",
                $"configured={_resolutionWidth}x{_resolutionHeight}@{_videoFps} " +
                $"bitrate={_bitrate} texture_fps={_textureUpdates / windowSeconds:F1} " +
                $"texture_failures={_textureFailures} " +
                $"polls={_decoderPolls} poll_avg_ms={averagePollMs:F3} " +
                $"poll_max_ms={_decoderPollMaxMs:F3} hitch_frames={_hitchFrames} " +
                $"frame_max_ms={_frameDeltaMaxMs:F1}");
            ResetPerformanceWindow();
        }
    }

    private void ResetPerformanceWindow()
    {
        float now = Time.realtimeSinceStartup;
        _nextHealthRealtime = now + HealthIntervalSeconds;
        _decoderPolls = 0;
        _textureUpdates = 0;
        _textureFailures = 0;
        _hitchFrames = 0;
        _decoderPollTotalMs = 0.0;
        _decoderPollMaxMs = 0.0;
        _frameDeltaMaxMs = 0f;
    }
}
