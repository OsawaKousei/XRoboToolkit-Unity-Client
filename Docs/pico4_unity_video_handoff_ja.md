# PICO4 Unity映像対応 引き継ぎ仕様

## 目的

Torobo頭部のIntel RealSense SR300カラー映像をROS 1からROS 2へ渡し、
ROS 2ホストでH.264に変換してPICO4へ表示する。同じ操作セッションについて、
映像、左右腕の操作目標、Toroboへ送信した指令、左右腕の実関節角度をrosbagへ残す。

この文書はUnity担当者とROS担当者への引き継ぎ仕様である。参照用の
`tmp/XRoboToolkit-Unity-Client`は変更せず、実際のUnityワークスペースで対応すること。

## 100Mbps対応後の目標システム構成

```text
Intel RealSense SR300
  │ USB 3
  ▼
ROS 1 PC bri@192.168.10.2 (Ubuntu 16.04 / ROS Kinetic)
  │ /torobo/head/camera/color/image_raw/compressed (JPEG, 30 fps)
  │ 100BASE-TX / ros1_bridge
  ▼
ROS 2ホスト
  有線: 192.168.10.1
  Wi-Fi: 192.168.88.173
  ROS_DOMAIN_ID: 30 / rmw_cyclonedds_cpp
  │
  ├─ rosbag: JPEG圧縮映像、左右腕指令・実角度
  └─ xrobot_camera_streamer: H.264/TCP
       │ Wi-Fi
       ▼
     PICO4: 192.168.88.190
```

ROS 2側の`xrobot_camera_streamer`はSR300の640x480単眼画像を左右に複製し、
1280x480のside-by-side映像にして送る。左右眼には同じ映像が表示されるため、
立体映像ではない。

## Unity担当者への依頼

### 必須変更

`Assets/StreamingAssets/video_source.yml`へ次の映像ソースを追加する。今回の実装では、
既存installの`persistentDataPath/video_source.yml`に同名profileがなければ、一度だけ
自動追記するmigrationも追加する。

```yaml
- name: "TOROBO_CAMERA"
  camera: "ROS_CAMERA"
  description: "Torobo SR300 mono camera (duplicated side-by-side)"
  properties:
    - name: "visibleRatio"
      type: "float"
      value: 0.555
    - name: "contentRatio"
      type: "float"
      value: 1.8
    - name: "heightCompressionFactor"
      type: "float"
      value: 1.333333
    - name: "RawImageRectSize"
      type: "string"
      value: "600x225"
    - name: "CamWidth"
      type: "int"
      value: 1280
    - name: "CamHeight"
      type: "int"
      value: 480
    - name: "CamFPS"
      type: "int"
      value: 30
    - name: "CamBitrate"
      type: "int"
      value: 4000000
```

この表示パラメータは既存`PICO4U`設定と同じ4:3の両眼表示を流用している。
実機表示でクロップや縦横比に問題があれば、映像プロトコルや解像度ではなく、
`visibleRatio`、`contentRatio`、`heightCompressionFactor`、`RawImageRectSize`を調整する。

PICO4上でRemote Visionを開き、`TOROBO_CAMERA`を選択して、映像ソースIPに
ROS 2ホストのWi-Fiアドレス`192.168.88.173`を入力できること。

設定の反映方法はinstall状態で異なる。

- 新規install: APK内の`Assets/StreamingAssets/video_source.yml`が初回起動時に
  `persistentDataPath`へcopyされる。
- 既存installの更新: 起動時migrationが既存の端末上ファイルへ
  `TOROBO_CAMERA`を追記する。手動確認・編集する場合はREADMEの手順どおり
  `adb pull` / `adb push`で
  `/sdcard/Android/data/com.xrobotoolkit.client/files/video_source.yml`を更新する。
- 端末側設定を全て初期化する場合は、端末上の`video_source.yml`を削除してから
  アプリを再起動し、StreamingAssets版を再copyさせる。

### 現在の制御・映像通信仕様

参照ソースの既存TCP経路をそのまま利用する。

- PICO4から`192.168.88.173:13579/TCP`へ接続する。
- 接続後、PICO4はセッション識別子を格納した`AUDIO_SESSION`を先に送り、続いて
  `OPEN_CAMERA`を送る。音声非対応の`xrobot_camera_streamer`は
  `AUDIO_SESSION`を安全に読み捨て、同じ接続上の次のcommandを処理する。
- `OPEN_CAMERA`のcamera値は`ROS_CAMERA`とする。
- PICO4は自身のIPと映像受信ポート`12345`を要求に格納する。
- H.264の送信先IPは要求内IPではなく、13579/TCPのpeer IPを正とする。
  要求内IPは一致確認とwarning logにのみ利用し、不一致だけを理由に正しい接続を
  拒否しない。これにより複数interface環境での誤拒否と第三者への転送を防ぐ。
- PICO4の`MediaDecoder`が`12345/TCP`でH.264を待ち受ける。
- ROS 2ホストからPICO4へ送る各H.264 access unitは、4-byte big-endianの
  データ長を先頭に付ける。
- codecはH.264/AVC baseline、Annex-B、1280x480、30 fps、4 Mbps。
- 1秒ごとにIDRを生成し、IDRにSPS/PPSを繰り返し含める。

現在のUnityはさらに`AUDIO_CONFIG` / `OPEN_CAMERA_ACK`の受信、control connection
監視、切断時の再接続とsession再交渉を行う。送信側は未知のcommandをprotocol error
として接続切断してはならず、command長とpayload長を使ってframe全体を読み捨て、
後続の既知commandを処理する。`CLOSE_CAMERA`を受けた場合は映像送信を停止する。

根拠となる参照実装:

- `Assets/Scripts/UI/UICameraCtrl.cs`: 制御先IP入力、13579/TCPへの接続、
  PICOローカルIPと12345番の通知
- `Assets/Scripts/Camera/RemoteCameraWindow.cs`: `MediaDecoder`の起動
- `Assets/Scripts/MediaDecoder.cs`: Android側TCP decoder wrapper
- `Assets/Scripts/Network/CameraRequestSerializer.cs`: `CA FE`、version 1、
  カメラ要求のlittle-endian形式
- `Assets/Scripts/Network/NetworkDataProtocolSerializer.cs`: `OPEN_CAMERA` / 
  `CLOSE_CAMERA`の内部形式
- `Assets/Plugins/Android/robotassistant_lib-i18n-release.aar`: 実際の
  `MediaDecoder`がTCP ServerSocketと4-byte big-endian長を使用

`MediaDecoderUDP`もリポジトリには存在するが、現在の
`RemoteCameraWindow`からは呼ばれていないため、UDP対応は不要。

### Unity側の終了処理

画面を閉じる、ListenをOFFにする、またはアプリを終了する際に次を確認する。

1. `CLOSE_CAMERA`を送信する。
2. `MediaDecoder.release()`で12345番のServerSocket、接続済みSocket、
   receive thread、MediaCodecを解放する。
3. 13579番の制御TCP clientをcloseする。
4. 再度Listenしたとき、同じポートで受信を再開できる。

今回更新したAndroid AARはServerSocketと接続済みSocketをmemberとして保持し、
`stopServer()`で両socketを先にcloseしてblocking中の`accept()` / `readFully()`を
解除してからreceive threadをjoinする。Unity側もListen OFF、画面非表示、pause、
component disable、destroy、application quitから冪等な停止処理を呼ぶ。

通常の再接続試験に加え、映像senderが12345/TCPへ一度も接続していない状態で
ListenをOFFにし、直後のListen ONで12345番を再利用できることをPICO4実機で確認する。

1280x480 SBSは、640x480単眼画像を左右に複製するため映像情報自体は増えないが、
現在のflat表示shaderが左眼でtexture左半分、右眼で右半分を参照することとの
互換layerである。第一段階では維持する。将来640x480 monoへ最適化する場合は、
両眼がfull textureを参照するようshaderも同時に変更する。

## 既存のROS側構成

ROS 2の`xrobotoolkit_teleop_with_service.launch.py`は次を既定で行う。

- ROS domain 30を使用
- RoboticsService、MoveIt、teleop、rosbag、映像送信を一括起動
- rosbagを`~/torobo_bags/xrobotoolkit_teleop_YYYYMMDD_HHMMSS`へ保存
- 空き容量が5 GiB未満なら起動を中止
- カメラ画像が15秒以内に届かなければ全体を停止
- 構成プロセスの一つが終了した場合は全体を停止
- 終了時に左右腕を明示的にservo-off
- joint-angle stream、XR接続、H.264 pipeline、TCP socketを解放
- RoboticsServiceをSIGINT、SIGTERM、必要時のみSIGKILLの順で終了

rosbag記録対象:

```text
/xrobot/raw/device_state_json
/xrobot/teleop/commanded_joint_state/left_arm
/xrobot/teleop/commanded_joint_state/right_arm
/torobo_operator/torobo_command/left_arm_controller
/torobo_operator/torobo_command/right_arm_controller
/torobo_operator/joint_angles/left_arm_controller
/torobo_operator/joint_angles/right_arm_controller
/torobo/head/camera/color/image_raw/compressed
/torobo/head/camera/color/camera_info
/xrobot/video_stream/status
```

## 100BASE-TX環境でROS側に必要な対応

### 背景と判断

ROS 2ホストの有線interface `enp6s0`は、実機確認時に次の状態だった。

```text
Speed: 100Mb/s
Duplex: Full
Link detected: yes
```

`640 x 480 x RGB 3 byte x 30 fps`のraw image payloadは約27.6 MB/s
（221 Mbps）であり、ROS serialization、TCP/IP等のoverheadを含める前から
100BASE-TXの物理帯域を超える。したがって現在のlink速度のまま
`sensor_msgs/Image`を30 fpsでbridgeする構成は採用しない。

1 GbEへ変更できる場合はraw image bridgeへ戻す方が、JPEG decode負荷と
二重の非可逆圧縮を避けられる。100Mbpsで運用する場合は、以下のcompressed経路を
ROS側へ実装する。

```text
SR300 sensor_msgs/Image (ROS 1 PC内)
  -> JPEG encode
  -> sensor_msgs/CompressedImage
  -> 100BASE-TX / ros1_bridge
  -> JPEG decode (ROS 2 host)
  -> 640x480 monoを左右複製して1280x480 SBS化
  -> H.264 encode
  -> 4-byte big-endian length + Annex-B H.264 / TCP
  -> PICO4:12345
```

### 必須対応と理由

| 対応 | 実装場所 | 理由 |
|---|---|---|
| SR300のraw imageをJPEGへ変換し、`sensor_msgs/CompressedImage`としてpublishする | ROS 1 PC | 221 Mbps以上となるraw imageを100Mbps区間へ流さないため |
| JPEG qualityをlaunch parameter化する（初期値の目安は80） | ROS 1 PC | 画質、帯域、encode負荷を実機映像に合わせて調整するため |
| ros1_bridgeではcompressed imageと`CameraInfo`をbridgeし、同じraw image topicを有線区間へ同時に流さない | bridge構成 | compressed経路を追加してもraw経路が残れば100Mbps linkが飽和するため |
| `xrobot_camera_streamer`を`CompressedImage`購読に対応させ、JPEGをdecodeする | ROS 2ホスト | SBS化とH.264 encodeにはpixel imageが必要なため |
| subscriber/publisher queueを原則1、または同等の「最新frame優先」にする | ROS 1、bridge、ROS 2 | 処理落ち時に古いframeが蓄積し、表示遅延が増え続けることを防ぐため |
| JPEG decode失敗、空data、想定外formatをframe単位で破棄し、processを停止させない | ROS 2ホスト | 一時的な破損やframe lossから次の正常frameで復帰するため |
| 入力解像度とframe rateを監視し、640x480以外は明示的に拒否または設定どおりresizeする | ROS 2ホスト | 意図しない引き伸ばし、encoder設定不一致、負荷急増を防ぐため |
| compressed入力の受信fps、帯域、JPEG decode時間、H.264 encode時間、drop数をstatusへ出す | ROS 2ホスト | 100Mbpsで余裕があるか、計算資源が十分か、遅延原因がどこかを判定するため |
| 13579/TCPのpeer IPをH.264送信先とし、要求内IPは照合・warning logだけに使う | `xrobot_camera_streamer` | 複数interfaceによる誤拒否と、別hostへの映像転送を防ぐため |
| `AUDIO_SESSION`等の未知または未対応commandをframe長に従って読み捨て、接続を維持する | `xrobot_camera_streamer` | 現行Unityが`OPEN_CAMERA`より先に音声session commandを送るため |
| `CLOSE_CAMERA`、control切断、SIGINT/SIGTERMでH.264 pipelineと全socketを解放する | `xrobot_camera_streamer` | Listen OFF/ONとlaunch再起動で13579/12345を再利用するため |

JPEGは画像内容によってframe sizeが変動する。100Mbpsの理論値を上限として使わず、
他のROS通信とprotocol overheadの余裕を確保する。運用開始時はcompressed imageを
継続60 Mbps以下、可能なら数十Mbps以下に収めることを目安とし、実測値によって
JPEG qualityまたはfpsを下げる。30 fpsで60 Mbpsは平均約250 KB/frameに相当する。

ROS 2側のJPEG decodeとH.264 encodeはpipelineとして30 fpsを維持できることが必要で、
単一frameの処理時間だけでなく、queue滞留とend-to-end latencyを監視する。
処理能力が不足した場合は古いframeを捨て、遅れて全frameを処理しない。

### ROS側の確認項目

ROS 1 PCでcompressed publisherを確認する。

```bash
rostopic hz /torobo/head/camera/color/image_raw/compressed
rostopic bw /torobo/head/camera/color/image_raw/compressed
rostopic echo -n 1 /torobo/head/camera/color/image_raw/compressed
```

ROS 2ホストでbridge後のtopicを確認する。

```bash
export ROS_DOMAIN_ID=30
ros2 topic info /torobo/head/camera/color/image_raw/compressed -v
ros2 topic hz /torobo/head/camera/color/image_raw/compressed
ros2 topic bw /torobo/head/camera/color/image_raw/compressed
ros2 topic echo /xrobot/video_stream/status --once
```

確認中は、raw topicが100Mbps区間を流れていないこともinterface trafficまたはbridgeの
topic一覧で確認する。少なくとも10分間連続運転し、次を合格条件とする。

1. compressed image入力とPICO表示が目標30 fpsを維持する。
2. 有線送信帯域に継続的な余裕があり、link上限へ張り付かない。
3. JPEG decode/H.264 encodeのqueueが増加せず、古いframeが破棄される。
4. PICO表示のend-to-end latencyが時間経過とともに増加しない。
5. JPEG破損frameや一時的なtopic断の後、process再起動なしで復帰する。
6. rosbagには従来どおりcompressed imageと`CameraInfo`が記録される。

### Unity側への影響

Unity側の入力はROS messageではなく、`xrobot_camera_streamer`が生成したH.264/TCPである。
ROS 1/ROS 2間をrawからJPEG compressedへ変更しても、streamerが従来どおり
1280x480 SBS、H.264/AVC、4-byte big-endian length prefix、PICO peer IPの
12345/TCPへ出力する限り、Unity側の追加変更は不要である。

JPEG decode後に640x480 monoのpixel imageへ戻す処理とSBS化はROS 2側の責務とする。
UnityへJPEGを直接送る、640x480 monoへ映像protocolを変更する、texture layoutを
変更する場合に限り、Unity decoderまたはshaderの追加変更が必要になる。

## 結合試験

ROS 1 PCでは実機bringup、bridge-facing service、SR300を起動する。

```bash
ssh bri@192.168.10.2
source /home/bri/torobo_bridged_ws/ros1_ws/scripts/ros1_setup.bash
roslaunch torobo_bringup torobo_bringup.launch \
  product:=Torobo18 sim:=false mock:=false rqt:=false rviz:=false

# 別のROS 1端末
source /home/bri/torobo_bridged_ws/ros1_ws/scripts/ros1_setup.bash
rosrun torobo_ctrl torobo_ctrl_node.py __ns:=torobo

# 別のROS 1端末
source /home/bri/torobo_bridged_ws/ros1_ws/scripts/ros1_setup.bash
roslaunch torobo_camera rs_color.launch
```

ROS 2ホストではbridgeを起動する。

```bash
cd /home/kousei/torobo_bridged_ws
bash bridge_ws/up_bridge.sh
```

最初の試験は安全のため、ROS 2 launchへ次を渡してモータ指令を無効化する。

```bash
ros2 launch torobo_ros2_controller xrobotoolkit_teleop_with_service.launch.py \
  command_enabled:=false move_to_initial_pose:=false \
  servo_on_for_initial_pose:=false
```

上記の安全試験では`command_enabled=false`なので、終了時servo-offのservice callも
行わない。実機指令を有効にした通常運用では、launch終了時に左右腕をservo-offする。

PICO4では`TOROBO_CAMERA`を選択し、`192.168.88.173`へListenする。

合格条件:

1. PICO4の左右眼に同じSR300カラー映像が正しい4:3比率で表示される。
2. 30 fpsを目標とし、継続的な遅延増加がない。
3. ListenのOFF/ON後に再接続できる。
4. launch終了後にPICO4の12345番とホストの13579番を再利用できる。
5. rosbagに映像とXR入力が記録される。
6. 有線linkが1 GbEならraw bridgeを使用できる。100Mbpsならcompressed bridgeを使い、
   raw imageを同時にbridgeせず、実測帯域と処理遅延が上記基準内に収まる。

`640 x 480 x RGB 3 byte x 30 fps`はraw payloadだけで約27.6 MB/s（221 Mbps）となり、
ROS serialization等のoverheadがさらに加わる。1 GbEならraw image bridgeを第一選択と
する。100BASE-TXの場合は30 fps raw bridgeを合格扱いにせず、ROS 1側で
`sensor_msgs/CompressedImage`へ変換する、fpsを落とす、pixel formatを変える等を
選択する。ただしJPEG decode後にH.264 encodeする追加負荷が生じるため、GbE環境で
圧縮bridgeへ変更しない。

安全試験完了後に`command_enabled:=true`と初期姿勢移動を有効化し、
左右腕の`ToroboCommand`と実関節角度がbagへ記録されることを確認する。

## ネットワーク確認

2026-09-11にROS 2ホストで次を確認済み。

- 有線`enp6s0`: `192.168.10.1/24`
- Wi-Fi`wlo1`: `192.168.88.173/24`
- PICO4の正しいWi-Fiアドレスは`192.168.88.190`（ユーザー確認）
- 更新時のpingには応答なし。スリープやICMP拒否の可能性があるため、Unity結合試験時にTCP接続で再確認する

UnityはAndroidで列挙された最初のIPv4を要求内へ格納するため、VPN、USB networking、
複数interface環境では実際のWi-Fi IPと異なる可能性がある。ROS 2送信側は13579/TCPの
接続元peer IPをH.264送信先として使用し、要求内IPは診断用に記録する。不一致時は
warningを出すが、それだけを理由に拒否しない。
