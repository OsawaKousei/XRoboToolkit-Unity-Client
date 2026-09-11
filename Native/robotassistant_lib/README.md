# robotassistant MediaDecoder override

The upstream AAR keeps its TCP sockets local to the receive thread. This source
rebuilds only `MediaDecoder.class` so `stopServer()` can close a blocked
`accept()` or `readFully()` before joining the thread.

Rebuild with an Android platform jar matching or preceding the Unity target:

```bash
Native/robotassistant_lib/tools/rebuild_media_decoder.sh \
  /path/to/Android/Sdk/platforms/android-32/android.jar
```
