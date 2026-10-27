# Reference-aligned FBO bridge

Reference: `/home/kousei/torobo_bridged_ws/tmp/XRoboToolkit-Unity-Client`.
The reference includes local changes; it is not an unmodified upstream baseline.
Its FBO implementation is distributed as `Assets/Plugins/Android/fbolibrary.aar`.

The public `FBOPlugin` API and MediaCodec → SurfaceTexture/OES → FBO → Unity
Texture2D path are retained. Rendering uses the reference's four vertices and
TRIANGLE_STRIP, with GL bottom-left base UVs. Unity polls `isUpdateFrame()`
in Update and calls `updateTexture()` only for a new frame. TCP framing, SBS layout,
MediaDecoder API, and control connection lifecycle are unchanged.

Intentional differences from the reference:

- RGBA32 plus initial Apply allocates the Unity attachment explicitly. This is
  a mitigation for the observed incomplete FBO, not proof RGB24 was the sole cause.
- OES uses LINEAR and CLAMP_TO_EDGE; forbidden mipmap operations are removed.
- A private VAO/VBO isolates vertex state; vertex data is uploaded once.
- The producer's SurfaceTexture transform is applied to GL bottom-left base UVs.
  The reference's pre-flipped UVs combined with this transform produced an
  upside-down headset image (reported on 2026-10-01), so that extra flip is removed.
- Modified GLES state is restored in finally, including separate read/draw FBOs,
  viewport, program, VAO, sampler, OES bindings, active unit and raster state.
- One atomic pending-frame flag replaces an accumulating frame counter.
- Owning EGL context is checked. Android must remain OpenGLES3 with multithreaded
  rendering disabled for the current direct-JNI calling model.
- FBO/draw errors propagate to Unity and increment texture_failures, not texture_fps.
  These counters measure successful copy calls, not headset presentation rate.
- Listen stop cancels initialization, closes sockets and releases textures.

Rebuild the AAR with `bash Native/fbolibrary/tools/rebuild_fbolibrary.sh`.
The script uses the installed Unity 2022.3.16f1 JDK and Android API 32 SDK.
The rebuilt AAR contains the compatible FBOPlugin class; the original obfuscated
helper classes are not retained. The reference directory is not modified.

Device acceptance (not established by Java/C# compilation): rebuild/sign pico.apk,
install it, check real images in both eyes and orientation/crop, then repeat Listen
OFF/ON. Include FBOPlugin, OpenGL, Unity and MediaDecoder in logcat. Require no
incomplete-FBO/context/shader errors, texture_failures=0, and stable visible video.
Run a ten-minute test for latency and resource stability. RGBA32, producer transform
and state restoration still require these on-device checks.
