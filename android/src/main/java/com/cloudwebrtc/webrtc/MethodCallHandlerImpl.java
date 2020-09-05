package com.cloudwebrtc.webrtc;

import static com.cloudwebrtc.webrtc.utils.MediaConstraintsUtils.parseMediaConstraints;

import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.util.Log;
import android.util.LongSparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cloudwebrtc.webrtc.record.AudioChannel;
import com.cloudwebrtc.webrtc.record.FrameCapturer;
import com.cloudwebrtc.webrtc.utils.AnyThreadResult;
import com.cloudwebrtc.webrtc.utils.ConstraintsArray;
import com.cloudwebrtc.webrtc.utils.ConstraintsMap;
import com.cloudwebrtc.webrtc.utils.EglUtils;
import com.cloudwebrtc.webrtc.utils.ObjectType;
import com.cloudwebrtc.webrtc.utils.RTCAudioManager;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import org.webrtc.AudioTrack;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaConstraints.KeyValuePair;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnection.BundlePolicy;
import org.webrtc.PeerConnection.CandidateNetworkPolicy;
import org.webrtc.PeerConnection.ContinualGatheringPolicy;
import org.webrtc.PeerConnection.IceServer;
import org.webrtc.PeerConnection.IceServer.Builder;
import org.webrtc.PeerConnection.IceTransportsType;
import org.webrtc.PeerConnection.KeyType;
import org.webrtc.PeerConnection.RTCConfiguration;
import org.webrtc.PeerConnection.RtcpMuxPolicy;
import org.webrtc.PeerConnection.SdpSemantics;
import org.webrtc.PeerConnection.TcpCandidatePolicy;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.PeerConnectionFactory.Options;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SessionDescription.Type;
import org.webrtc.VideoTrack;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

public class MethodCallHandlerImpl implements MethodCallHandler, StateProvider {


  interface AudioManager {

    void onAudioManagerRequested(boolean requested);

    void setMicrophoneMute(boolean mute);

    void setSpeakerphoneOn(boolean on);


  }

  static public final String TAG = "FlutterWebRTCPlugin";

  private final Map<String, PeerConnectionObserver> mPeerConnectionObservers = new HashMap<>();
  private BinaryMessenger messenger;
  private Context context;
  private final TextureRegistry textures;

  private PeerConnectionFactory mFactory;

  private final Map<String, MediaStream> localStreams = new HashMap<>();
  private final Map<String, MediaStreamTrack> localTracks = new HashMap<>();

  private LongSparseArray<FlutterRTCVideoRenderer> renders = new LongSparseArray<>();

  /**
   * The implementation of {@code getUserMedia} extracted into a separate file in order to reduce
   * complexity and to (somewhat) separate concerns.
   */
  private GetUserMediaImpl getUserMediaImpl;

  private final AudioManager audioManager;

  private AudioDeviceModule audioDeviceModule;

  private Activity activity;

  MethodCallHandlerImpl(Context context, BinaryMessenger messenger, TextureRegistry textureRegistry,
      @NonNull AudioManager audioManager) {
    this.context = context;
    this.textures = textureRegistry;
    this.messenger = messenger;
    this.audioManager = audioManager;
  }

  void dispose() {
    mPeerConnectionObservers.clear();
  }

  private void ensureInitialized() {
    if (mFactory != null) {
      return;
    }

    PeerConnectionFactory.initialize(
        InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions());

    // Initialize EGL contexts required for HW acceleration.
    EglBase.Context eglContext = EglUtils.getRootEglBaseContext();

    getUserMediaImpl = new GetUserMediaImpl(this, context);

    audioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .setSamplesReadyCallback(getUserMediaImpl.inputSamplesInterceptor)
        .createAudioDeviceModule();

    getUserMediaImpl.audioDeviceModule = (JavaAudioDeviceModule) audioDeviceModule;

    mFactory = PeerConnectionFactory.builder()
        .setOptions(new Options())
        .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglContext, false, true))
        .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglContext))
        .setAudioDeviceModule(audioDeviceModule)
        .createPeerConnectionFactory();
  }

  @Override
  public void onMethodCall(MethodCall call, @NonNull Result notSafeResult) {
    ensureInitialized();

    final AnyThreadResult result = new AnyThreadResult(notSafeResult);
    switch (call.method) {
      case "createPeerConnection": {
        Map<String, Object> constraints = call.argument("constraints");
        Map<String, Object> configuration = call.argument("configuration");
        String peerConnectionId = peerConnectionInit(new ConstraintsMap(configuration),
            new ConstraintsMap((constraints)));
        ConstraintsMap res = new ConstraintsMap();
        res.putString("peerConnectionId", peerConnectionId);
        result.success(res.toMap());
        break;
      }
      case "getUserMedia": {
        Map<String, Object> constraints = call.argument("constraints");
        ConstraintsMap constraintsMap = new ConstraintsMap(constraints);
        getUserMedia(constraintsMap, result);
        break;
      }
      case "createLocalMediaStream":
        createLocalMediaStream(result);
        break;
      case "getSources":
        getSources(result);
        break;
      case "createOffer": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> constraints = call.argument("constraints");
        peerConnectionCreateOffer(peerConnectionId, new ConstraintsMap(constraints), result);
        break;
      }
      case "createAnswer": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> constraints = call.argument("constraints");
        peerConnectionCreateAnswer(peerConnectionId, new ConstraintsMap(constraints), result);
        break;
      }
      case "mediaStreamGetTracks": {
        String streamId = call.argument("streamId");
        MediaStream stream = getStreamForId(streamId, "");
        Map<String, Object> resultMap = new HashMap<>();
        List<Object> audioTracks = new ArrayList<>();
        List<Object> videoTracks = new ArrayList<>();
        for (AudioTrack track : stream.audioTracks) {
          localTracks.put(track.id(), track);
          Map<String, Object> trackMap = new HashMap<>();
          trackMap.put("enabled", track.enabled());
          trackMap.put("id", track.id());
          trackMap.put("kind", track.kind());
          trackMap.put("label", track.id());
          trackMap.put("readyState", "live");
          trackMap.put("remote", false);
          audioTracks.add(trackMap);
        }
        for (VideoTrack track : stream.videoTracks) {
          localTracks.put(track.id(), track);
          Map<String, Object> trackMap = new HashMap<>();
          trackMap.put("enabled", track.enabled());
          trackMap.put("id", track.id());
          trackMap.put("kind", track.kind());
          trackMap.put("label", track.id());
          trackMap.put("readyState", "live");
          trackMap.put("remote", false);
          videoTracks.add(trackMap);
        }
        resultMap.put("audioTracks", audioTracks);
        resultMap.put("videoTracks", videoTracks);
        result.success(resultMap);
        break;
      }
      case "addStream": {
        String streamId = call.argument("streamId");
        String peerConnectionId = call.argument("peerConnectionId");
        peerConnectionAddStream(streamId, peerConnectionId, result);
        break;
      }
      case "removeStream": {
        String streamId = call.argument("streamId");
        String peerConnectionId = call.argument("peerConnectionId");
        peerConnectionRemoveStream(streamId, peerConnectionId, result);
        break;
      }
      case "setLocalDescription": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> description = call.argument("description");
        peerConnectionSetLocalDescription(new ConstraintsMap(description), peerConnectionId,
            result);
        break;
      }
      case "setRemoteDescription": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> description = call.argument("description");
        peerConnectionSetRemoteDescription(new ConstraintsMap(description), peerConnectionId,
            result);
        break;
      }
      case "addCandidate": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> candidate = call.argument("candidate");
        peerConnectionAddICECandidate(new ConstraintsMap(candidate), peerConnectionId, result);
        break;
      }
      case "getStats": {
        String peerConnectionId = call.argument("peerConnectionId");
        String trackId = call.argument("trackId");
        peerConnectionGetStats(trackId, peerConnectionId, result);
        break;
      }
      case "createDataChannel": {
        String peerConnectionId = call.argument("peerConnectionId");
        String label = call.argument("label");
        Map<String, Object> dataChannelDict = call.argument("dataChannelDict");
        createDataChannel(peerConnectionId, label, new ConstraintsMap(dataChannelDict), result);
        break;
      }
      case "dataChannelSend": {
        String peerConnectionId = call.argument("peerConnectionId");
        int dataChannelId = call.argument("dataChannelId");
        String type = call.argument("type");
        Boolean isBinary = type.equals("binary");
        ByteBuffer byteBuffer;
        if (isBinary) {
          byteBuffer = ByteBuffer.wrap(call.argument("data"));
        } else {
          try {
            String data = call.argument("data");
            byteBuffer = ByteBuffer.wrap(data.getBytes("UTF-8"));
          } catch (UnsupportedEncodingException e) {
            Log.d(TAG, "Could not encode text string as UTF-8.");
            result.error("dataChannelSendFailed", "Could not encode text string as UTF-8.", null);
            return;
          }
        }
        dataChannelSend(peerConnectionId, dataChannelId, byteBuffer, isBinary);
        result.success(null);
        break;
      }
      case "dataChannelClose": {
        String peerConnectionId = call.argument("peerConnectionId");
        int dataChannelId = call.argument("dataChannelId");
        dataChannelClose(peerConnectionId, dataChannelId);
        result.success(null);
        break;
      }
      case "streamDispose": {
        String streamId = call.argument("streamId");
        mediaStreamRelease(streamId);
        result.success(null);
        break;
      }
      case "mediaStreamTrackSetEnable": {
        String trackId = call.argument("trackId");
        Boolean enabled = call.argument("enabled");
        MediaStreamTrack track = getTrackForId(trackId);
        if (track != null) {
          track.setEnabled(enabled);
        }
        result.success(null);
        break;
      }
      case "mediaStreamAddTrack": {
        String streamId = call.argument("streamId");
        String trackId = call.argument("trackId");
        mediaStreamAddTrack(streamId, trackId, result);
        break;
      }
      case "mediaStreamRemoveTrack": {
        String streamId = call.argument("streamId");
        String trackId = call.argument("trackId");
        mediaStreamRemoveTrack(streamId, trackId, result);
        break;
      }
      case "trackDispose": {
        String trackId = call.argument("trackId");
        localTracks.remove(trackId);
        result.success(null);
        break;
      }
      case "peerConnectionClose": {
        String peerConnectionId = call.argument("peerConnectionId");
        peerConnectionClose(peerConnectionId);
        result.success(null);
        break;
      }
      case "peerConnectionDispose": {
        String peerConnectionId = call.argument("peerConnectionId");
        peerConnectionDispose(peerConnectionId);
        result.success(null);
        break;
      }
      case "createVideoRenderer": {
        SurfaceTextureEntry entry = textures.createSurfaceTexture();
        SurfaceTexture surfaceTexture = entry.surfaceTexture();
        FlutterRTCVideoRenderer render = new FlutterRTCVideoRenderer(surfaceTexture, entry);
        renders.put(entry.id(), render);

        EventChannel eventChannel =
            new EventChannel(
                messenger,
                "FlutterWebRTC/Texture" + entry.id());

        eventChannel.setStreamHandler(render);
        render.setEventChannel(eventChannel);
        render.setId((int) entry.id());

        ConstraintsMap params = new ConstraintsMap();
        params.putInt("textureId", (int) entry.id());
        result.success(params.toMap());
        break;
      }
      case "videoRendererDispose": {
        int textureId = call.argument("textureId");
        FlutterRTCVideoRenderer render = renders.get(textureId);
        if (render == null) {
          result.error("FlutterRTCVideoRendererNotFound", "render [" + textureId + "] not found !",
              null);
          return;
        }
        render.Dispose();
        renders.delete(textureId);
        result.success(null);
        break;
      }
      case "videoRendererSetSrcObject": {
        int textureId = call.argument("textureId");
        String streamId = call.argument("streamId");
        String peerConnectionId = call.argument("ownerTag");
        FlutterRTCVideoRenderer render = renders.get(textureId);

        if (render == null) {
          result.error("FlutterRTCVideoRendererNotFound", "render [" + textureId + "] not found !",
              null);
          return;
        }

        MediaStream stream = getStreamForId(streamId, peerConnectionId);
        render.setStream(stream);
        result.success(null);
        break;
      }
      case "mediaStreamTrackHasTorch": {
        String trackId = call.argument("trackId");
        getUserMediaImpl.hasTorch(trackId, result);
        break;
      }
      case "mediaStreamTrackSetTorch": {
        String trackId = call.argument("trackId");
        boolean torch = call.argument("torch");
        getUserMediaImpl.setTorch(trackId, torch, result);
        break;
      }
      case "mediaStreamTrackSwitchCamera": {
        String trackId = call.argument("trackId");
        getUserMediaImpl.switchCamera(trackId, result);
        break;
      }
      case "setVolume": {
        String trackId = call.argument("trackId");
        double volume = call.argument("volume");
        mediaStreamTrackSetVolume(trackId, volume);
        result.success(null);
        break;
      }
      case "setMicrophoneMute":
        boolean mute = call.argument("mute");
        audioManager.setMicrophoneMute(mute);
        result.success(null);
        break;
      case "enableSpeakerphone":
        boolean enable = call.argument("enable");
        audioManager.setSpeakerphoneOn(enable);
        result.success(null);
        break;
      case "getDisplayMedia": {
        Map<String, Object> constraints = call.argument("constraints");
        ConstraintsMap constraintsMap = new ConstraintsMap(constraints);
        getDisplayMedia(constraintsMap, result);
        break;
      }
      case "startRecordToFile":
        //This method can a lot of different exceptions
        //so we should notify plugin user about them
        try {
          String path = call.argument("path");
          VideoTrack videoTrack = null;
          String videoTrackId = call.argument("videoTrackId");
          if (videoTrackId != null) {
            MediaStreamTrack track = getTrackForId(videoTrackId);
            if (track instanceof VideoTrack) {
              videoTrack = (VideoTrack) track;
            }
          }
          AudioChannel audioChannel = null;
          if (call.hasArgument("audioChannel")) {
            audioChannel = AudioChannel.values()[(Integer) call.argument("audioChannel")];
          }
          Integer recorderId = call.argument("recorderId");
          if (videoTrack != null || audioChannel != null) {
            getUserMediaImpl.startRecordingToFile(path, recorderId, videoTrack, audioChannel);
            result.success(null);
          } else {
            result.error("0", "No tracks", null);
          }
        } catch (Exception e) {
          result.error("-1", e.getMessage(), e);
        }
        break;
      case "stopRecordToFile":
        Integer recorderId = call.argument("recorderId");
        getUserMediaImpl.stopRecording(recorderId);
        result.success(null);
        break;
      case "captureFrame":
        String path = call.argument("path");
        String videoTrackId = call.argument("trackId");
        if (videoTrackId != null) {
          MediaStreamTrack track = getTrackForId(videoTrackId);
          if (track instanceof VideoTrack) {
            new FrameCapturer((VideoTrack) track, new File(path), result);
          } else {
            result.error(null, "It's not video track", null);
          }
        } else {
          result.error(null, "Track is null", null);
        }
        break;
      case "getLocalDescription": {
        String peerConnectionId = call.argument("peerConnectionId");
        PeerConnection peerConnection = getPeerConnection(peerConnectionId);
        if (peerConnection != null) {
          SessionDescription sdp = peerConnection.getLocalDescription();
          ConstraintsMap params = new ConstraintsMap();
          params.putString("sdp", sdp.description);
          params.putString("type", sdp.type.canonicalForm());
          result.success(params.toMap());
        } else {
          Log.d(TAG, "getLocalDescription() peerConnection is null");
          result.error("getLocalDescriptionFailed", "getLocalDescription() peerConnection is null",
              null);
        }
        break;
      }
      case "getRemoteDescription": {
        String peerConnectionId = call.argument("peerConnectionId");
        PeerConnection peerConnection = getPeerConnection(peerConnectionId);
        if (peerConnection != null) {
          SessionDescription sdp = peerConnection.getRemoteDescription();
          ConstraintsMap params = new ConstraintsMap();
          params.putString("sdp", sdp.description);
          params.putString("type", sdp.type.canonicalForm());
          result.success(params.toMap());
        } else {
          Log.d(TAG, "getRemoteDescription() peerConnection is null");
          result
              .error("getRemoteDescriptionFailed", "getRemoteDescription() peerConnection is null",
                  null);
        }
        break;
      }
      case "setConfiguration": {
        String peerConnectionId = call.argument("peerConnectionId");
        Map<String, Object> configuration = call.argument("configuration");
        PeerConnection peerConnection = getPeerConnection(peerConnectionId);
        if (peerConnection != null) {
          peerConnectionSetConfiguration(new ConstraintsMap(configuration), peerConnection);
          result.success(null);
        } else {
          Log.d(TAG, "setConfiguration() peerConnection is null");
          result.error("setConfigurationFailed", "setConfiguration() peerConnection is null", null);
        }
        break;
      }
      default:
        result.notImplemented();
        break;
    }
  }

  private PeerConnection getPeerConnection(String id) {
    PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
    return (pco == null) ? null : pco.getPeerConnection();
  }

  private List<IceServer> createIceServers(ConstraintsArray iceServersArray) {
    final int size = (iceServersArray == null) ? 0 : iceServersArray.size();
    List<IceServer> iceServers = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      ConstraintsMap iceServerMap = iceServersArray.getMap(i);
      boolean hasUsernameAndCredential =
          iceServerMap.hasKey("username") && iceServerMap.hasKey("credential");
      if (iceServerMap.hasKey("url")) {
        if (hasUsernameAndCredential) {
          iceServers.add(IceServer.builder(iceServerMap.getString("url"))
              .setUsername(iceServerMap.getString("username"))
              .setPassword(iceServerMap.getString("credential")).createIceServer());
        } else {
          iceServers.add(
              IceServer.builder(iceServerMap.getString("url")).createIceServer());
        }
      } else if (iceServerMap.hasKey("urls")) {
        switch (iceServerMap.getType("urls")) {
          case String:
            if (hasUsernameAndCredential) {
              iceServers.add(IceServer.builder(iceServerMap.getString("urls"))
                  .setUsername(iceServerMap.getString("username"))
                  .setPassword(iceServerMap.getString("credential")).createIceServer());
            } else {
              iceServers.add(IceServer.builder(iceServerMap.getString("urls"))
                  .createIceServer());
            }
            break;
          case Array:
            ConstraintsArray urls = iceServerMap.getArray("urls");
            List<String> urlsList = new ArrayList<>();

            for (int j = 0; j < urls.size(); j++) {
              urlsList.add(urls.getString(j));
            }

            Builder builder = IceServer.builder(urlsList);

            if (hasUsernameAndCredential) {
              builder
                  .setUsername(iceServerMap.getString("username"))
                  .setPassword(iceServerMap.getString("credential"));
            }

            iceServers.add(builder.createIceServer());

            break;
        }
      }
    }
    return iceServers;
  }

  private RTCConfiguration parseRTCConfiguration(ConstraintsMap map) {
    ConstraintsArray iceServersArray = null;
    if (map != null) {
      iceServersArray = map.getArray("iceServers");
    }
    List<IceServer> iceServers = createIceServers(iceServersArray);
    RTCConfiguration conf = new RTCConfiguration(iceServers);
    if (map == null) {
      return conf;
    }

    // iceTransportPolicy (public api)
    if (map.hasKey("iceTransportPolicy")
        && map.getType("iceTransportPolicy") == ObjectType.String) {
      final String v = map.getString("iceTransportPolicy");
      if (v != null) {
        switch (v) {
          case "all": // public
            conf.iceTransportsType = IceTransportsType.ALL;
            break;
          case "relay": // public
            conf.iceTransportsType = IceTransportsType.RELAY;
            break;
          case "nohost":
            conf.iceTransportsType = IceTransportsType.NOHOST;
            break;
          case "none":
            conf.iceTransportsType = IceTransportsType.NONE;
            break;
        }
      }
    }

    // bundlePolicy (public api)
    if (map.hasKey("bundlePolicy")
        && map.getType("bundlePolicy") == ObjectType.String) {
      final String v = map.getString("bundlePolicy");
      if (v != null) {
        switch (v) {
          case "balanced": // public
            conf.bundlePolicy = BundlePolicy.BALANCED;
            break;
          case "max-compat": // public
            conf.bundlePolicy = BundlePolicy.MAXCOMPAT;
            break;
          case "max-bundle": // public
            conf.bundlePolicy = BundlePolicy.MAXBUNDLE;
            break;
        }
      }
    }

    // rtcpMuxPolicy (public api)
    if (map.hasKey("rtcpMuxPolicy")
        && map.getType("rtcpMuxPolicy") == ObjectType.String) {
      final String v = map.getString("rtcpMuxPolicy");
      if (v != null) {
        switch (v) {
          case "negotiate": // public
            conf.rtcpMuxPolicy = RtcpMuxPolicy.NEGOTIATE;
            break;
          case "require": // public
            conf.rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE;
            break;
        }
      }
    }

    // FIXME: peerIdentity of type DOMString (public api)
    // FIXME: certificates of type sequence<RTCCertificate> (public api)

    // iceCandidatePoolSize of type unsigned short, defaulting to 0
    if (map.hasKey("iceCandidatePoolSize")
        && map.getType("iceCandidatePoolSize") == ObjectType.Number) {
      final int v = map.getInt("iceCandidatePoolSize");
      if (v > 0) {
        conf.iceCandidatePoolSize = v;
      }
    }

    // sdpSemantics
    if (map.hasKey("sdpSemantics")
        && map.getType("sdpSemantics") == ObjectType.String) {
      final String v = map.getString("sdpSemantics");
      if (v != null) {
        switch (v) {
          case "plan-b":
            conf.sdpSemantics = SdpSemantics.PLAN_B;
            break;
          case "unified-plan":
            conf.sdpSemantics = SdpSemantics.UNIFIED_PLAN;
            break;
        }
      }
    }

    // === below is private api in webrtc ===

    // tcpCandidatePolicy (private api)
    if (map.hasKey("tcpCandidatePolicy")
        && map.getType("tcpCandidatePolicy") == ObjectType.String) {
      final String v = map.getString("tcpCandidatePolicy");
      if (v != null) {
        switch (v) {
          case "enabled":
            conf.tcpCandidatePolicy = TcpCandidatePolicy.ENABLED;
            break;
          case "disabled":
            conf.tcpCandidatePolicy = TcpCandidatePolicy.DISABLED;
            break;
        }
      }
    }

    // candidateNetworkPolicy (private api)
    if (map.hasKey("candidateNetworkPolicy")
        && map.getType("candidateNetworkPolicy") == ObjectType.String) {
      final String v = map.getString("candidateNetworkPolicy");
      if (v != null) {
        switch (v) {
          case "all":
            conf.candidateNetworkPolicy = CandidateNetworkPolicy.ALL;
            break;
          case "low_cost":
            conf.candidateNetworkPolicy = CandidateNetworkPolicy.LOW_COST;
            break;
        }
      }
    }

    // KeyType (private api)
    if (map.hasKey("keyType")
        && map.getType("keyType") == ObjectType.String) {
      final String v = map.getString("keyType");
      if (v != null) {
        switch (v) {
          case "RSA":
            conf.keyType = KeyType.RSA;
            break;
          case "ECDSA":
            conf.keyType = KeyType.ECDSA;
            break;
        }
      }
    }

    // continualGatheringPolicy (private api)
    if (map.hasKey("continualGatheringPolicy")
        && map.getType("continualGatheringPolicy") == ObjectType.String) {
      final String v = map.getString("continualGatheringPolicy");
      if (v != null) {
        switch (v) {
          case "gather_once":
            conf.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_ONCE;
            break;
          case "gather_continually":
            conf.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY;
            break;
        }
      }
    }

    // audioJitterBufferMaxPackets (private api)
    if (map.hasKey("audioJitterBufferMaxPackets")
        && map.getType("audioJitterBufferMaxPackets") == ObjectType.Number) {
      final int v = map.getInt("audioJitterBufferMaxPackets");
      if (v > 0) {
        conf.audioJitterBufferMaxPackets = v;
      }
    }

    // iceConnectionReceivingTimeout (private api)
    if (map.hasKey("iceConnectionReceivingTimeout")
        && map.getType("iceConnectionReceivingTimeout") == ObjectType.Number) {
      final int v = map.getInt("iceConnectionReceivingTimeout");
      conf.iceConnectionReceivingTimeout = v;
    }

    // iceBackupCandidatePairPingInterval (private api)
    if (map.hasKey("iceBackupCandidatePairPingInterval")
        && map.getType("iceBackupCandidatePairPingInterval") == ObjectType.Number) {
      final int v = map.getInt("iceBackupCandidatePairPingInterval");
      conf.iceBackupCandidatePairPingInterval = v;
    }

    // audioJitterBufferFastAccelerate (private api)
    if (map.hasKey("audioJitterBufferFastAccelerate")
        && map.getType("audioJitterBufferFastAccelerate") == ObjectType.Boolean) {
      final boolean v = map.getBoolean("audioJitterBufferFastAccelerate");
      conf.audioJitterBufferFastAccelerate = v;
    }

    // pruneTurnPorts (private api)
    if (map.hasKey("pruneTurnPorts")
        && map.getType("pruneTurnPorts") == ObjectType.Boolean) {
      final boolean v = map.getBoolean("pruneTurnPorts");
      conf.pruneTurnPorts = v;
    }

    // presumeWritableWhenFullyRelayed (private api)
    if (map.hasKey("presumeWritableWhenFullyRelayed")
        && map.getType("presumeWritableWhenFullyRelayed") == ObjectType.Boolean) {
      final boolean v = map.getBoolean("presumeWritableWhenFullyRelayed");
      conf.presumeWritableWhenFullyRelayed = v;
    }

    return conf;
  }

  public String peerConnectionInit(ConstraintsMap configuration, ConstraintsMap constraints) {
    String peerConnectionId = getNextStreamUUID();
    PeerConnectionObserver observer = new PeerConnectionObserver(this, messenger, peerConnectionId);
    PeerConnection peerConnection
        = mFactory.createPeerConnection(
        parseRTCConfiguration(configuration),
        parseMediaConstraints(constraints),
        observer);
    observer.setPeerConnection(peerConnection);
    if (mPeerConnectionObservers.size() == 0) {
      audioManager.onAudioManagerRequested(true);
    }
    mPeerConnectionObservers.put(peerConnectionId, observer);
    return peerConnectionId;
  }

  @Override
  public Map<String, MediaStream> getLocalStreams() {
    return localStreams;
  }

  @Override
  public Map<String, MediaStreamTrack> getLocalTracks() {
    return localTracks;
  }

  @Override
  public String getNextStreamUUID() {
    String uuid;

    do {
      uuid = UUID.randomUUID().toString();
    } while (getStreamForId(uuid, "") != null);

    return uuid;
  }

  @Override
  public String getNextTrackUUID() {
    String uuid;

    do {
      uuid = UUID.randomUUID().toString();
    } while (getTrackForId(uuid) != null);

    return uuid;
  }

  @Override
  public PeerConnectionFactory getPeerConnectionFactory() {
    return mFactory;
  }

  @Nullable
  @Override
  public Activity getActivity() {
    return activity;
  }

  MediaStream getStreamForId(String id, String peerConnectionId) {
    MediaStream stream = localStreams.get(id);

    if (stream == null) {
      if (peerConnectionId.length() > 0) {
        PeerConnectionObserver pco = mPeerConnectionObservers.get(peerConnectionId);
        stream = pco.remoteStreams.get(id);
      } else {
        for (Entry<String, PeerConnectionObserver> entry : mPeerConnectionObservers
            .entrySet()) {
          PeerConnectionObserver pco = entry.getValue();
          stream = pco.remoteStreams.get(id);
          if (stream != null) {
            break;
          }
        }
      }
    }

    return stream;
  }

  private MediaStreamTrack getTrackForId(String trackId) {
    MediaStreamTrack track = localTracks.get(trackId);

    if (track == null) {
      for (Entry<String, PeerConnectionObserver> entry : mPeerConnectionObservers.entrySet()) {
        PeerConnectionObserver pco = entry.getValue();
        track = pco.remoteTracks.get(trackId);
        if (track != null) {
          break;
        }
      }
    }

    return track;
  }


  public void getUserMedia(ConstraintsMap constraints, Result result) {
    String streamId = getNextStreamUUID();
    MediaStream mediaStream = mFactory.createLocalMediaStream(streamId);

    if (mediaStream == null) {
      // XXX The following does not follow the getUserMedia() algorithm
      // specified by
      // https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-getusermedia
      // with respect to distinguishing the various causes of failure.
      result.error(
          /* type */ "getUserMediaFailed",
          "Failed to create new media stream", null);
      return;
    }

    getUserMediaImpl.getUserMedia(constraints, result, mediaStream);
  }

  public void getDisplayMedia(ConstraintsMap constraints, Result result) {
    String streamId = getNextStreamUUID();
    MediaStream mediaStream = mFactory.createLocalMediaStream(streamId);

    if (mediaStream == null) {
      // XXX The following does not follow the getUserMedia() algorithm
      // specified by
      // https://www.w3.org/TR/mediacapture-streams/#dom-mediadevices-getusermedia
      // with respect to distinguishing the various causes of failure.
      result.error(
          /* type */ "getDisplayMedia",
          "Failed to create new media stream", null);
      return;
    }

    getUserMediaImpl.getDisplayMedia(constraints, result, mediaStream);
  }

  public void getSources(Result result) {
    ConstraintsArray array = new ConstraintsArray();
    String[] names = new String[Camera.getNumberOfCameras()];

    for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
      ConstraintsMap info = getCameraInfo(i);
      if (info != null) {
        array.pushMap(info);
      }
    }

    ConstraintsMap audio = new ConstraintsMap();
    audio.putString("label", "Audio");
    audio.putString("deviceId", "audio-1");
    audio.putString("facing", "");
    audio.putString("kind", "audioinput");
    array.pushMap(audio);
    
    ConstraintsMap map = new ConstraintsMap();
    map.putArray("sources", array.toArrayList());
    
    result.success(map.toMap());
  }

  private void createLocalMediaStream(Result result) {
    String streamId = getNextStreamUUID();
    MediaStream mediaStream = mFactory.createLocalMediaStream(streamId);
    localStreams.put(streamId, mediaStream);

    if (mediaStream == null) {
      result.error(/* type */ "createLocalMediaStream", "Failed to create new media stream", null);
      return;
    }
    Map<String, Object> resultMap = new HashMap<>();
    resultMap.put("streamId", mediaStream.getId());
    result.success(resultMap);
  }

  public void mediaStreamTrackStop(final String id) {
    // Is this functionality equivalent to `mediaStreamTrackRelease()` ?
    // if so, we should merge this two and remove track from stream as well.
    MediaStreamTrack track = localTracks.get(id);
    if (track == null) {
      Log.d(TAG, "mediaStreamTrackStop() track is null");
      return;
    }
    track.setEnabled(false);
    if (track.kind().equals("video")) {
      getUserMediaImpl.removeVideoCapturer(id);
    }
    localTracks.remove(id);
    // What exactly does `detached` mean in doc?
    // see: https://www.w3.org/TR/mediacapture-streams/#track-detached
  }

  public void mediaStreamTrackSetEnabled(final String id, final boolean enabled) {
    MediaStreamTrack track = localTracks.get(id);
    if (track == null) {
      Log.d(TAG, "mediaStreamTrackSetEnabled() track is null");
      return;
    } else if (track.enabled() == enabled) {
      return;
    }
    track.setEnabled(enabled);
  }

  public void mediaStreamTrackSetVolume(final String id, final double volume) {
    MediaStreamTrack track = localTracks.get(id);
    if (track != null && track instanceof AudioTrack) {
      Log.d(TAG, "setVolume(): " + id + "," + volume);
      try {
        ((AudioTrack) track).setVolume(volume);
      } catch (Exception e) {
        Log.e(TAG, "setVolume(): error", e);
      }
    } else {
      Log.w(TAG, "setVolume(): track not found: " + id);
    }
  }

  public void mediaStreamAddTrack(final String streaemId, final String trackId, Result result) {
    MediaStream mediaStream = localStreams.get(streaemId);
    if (mediaStream != null) {
      MediaStreamTrack track = localTracks.get(trackId);
      if (track != null) {
        if (track.kind().equals("audio")) {
          mediaStream.addTrack((AudioTrack) track);
        } else if (track.kind().equals("video")) {
          mediaStream.addTrack((VideoTrack) track);
        }
      } else {
        String errorMsg = "mediaStreamAddTrack() track [" + trackId + "] is null";
        Log.d(TAG, errorMsg);
        result.error("mediaStreamAddTrack", errorMsg, null);
      }
    } else {
      String errorMsg = "mediaStreamAddTrack() stream [" + trackId + "] is null";
      Log.d(TAG, errorMsg);
      result.error("mediaStreamAddTrack", errorMsg, null);
    }
    result.success(null);
  }

  public void mediaStreamRemoveTrack(final String streaemId, final String trackId, Result result) {
    MediaStream mediaStream = localStreams.get(streaemId);
    if (mediaStream != null) {
      MediaStreamTrack track = localTracks.get(trackId);
      if (track != null) {
        if (track.kind().equals("audio")) {
          mediaStream.removeTrack((AudioTrack) track);
        } else if (track.kind().equals("video")) {
          mediaStream.removeTrack((VideoTrack) track);
        }
      } else {
        String errorMsg = "mediaStreamRemoveTrack() track [" + trackId + "] is null";
        Log.d(TAG, errorMsg);
        result.error("mediaStreamRemoveTrack", errorMsg, null);
      }
    } else {
      String errorMsg = "mediaStreamRemoveTrack() stream [" + trackId + "] is null";
      Log.d(TAG, errorMsg);
      result.error("mediaStreamRemoveTrack", errorMsg, null);
    }
    result.success(null);
  }

  public void mediaStreamTrackRelease(final String streamId, final String _trackId) {
    MediaStream stream = localStreams.get(streamId);
    if (stream == null) {
      Log.d(TAG, "mediaStreamTrackRelease() stream is null");
      return;
    }
    MediaStreamTrack track = localTracks.get(_trackId);
    if (track == null) {
      Log.d(TAG, "mediaStreamTrackRelease() track is null");
      return;
    }
    track.setEnabled(false); // should we do this?
    localTracks.remove(_trackId);
    if (track.kind().equals("audio")) {
      stream.removeTrack((AudioTrack) track);
    } else if (track.kind().equals("video")) {
      stream.removeTrack((VideoTrack) track);
      getUserMediaImpl.removeVideoCapturer(_trackId);
    }
  }

  public ConstraintsMap getCameraInfo(int index) {
    CameraInfo info = new CameraInfo();

    try {
      Camera.getCameraInfo(index, info);
    } catch (Exception e) {
      Logging.e("CameraEnumerationAndroid", "getCameraInfo failed on index " + index, e);
      return null;
    }
    ConstraintsMap params = new ConstraintsMap();
    String facing = info.facing == 1 ? "front" : "back";
    params.putString("label",
        "Camera " + index + ", Facing " + facing + ", Orientation " + info.orientation);
    params.putString("deviceId", "" + index);
    params.putString("facing", facing);
    params.putString("kind", "videoinput");
    return params;
  }

  private MediaConstraints defaultConstraints() {
    MediaConstraints constraints = new MediaConstraints();
    // TODO video media
    constraints.mandatory.add(new KeyValuePair("OfferToReceiveAudio", "true"));
    constraints.mandatory.add(new KeyValuePair("OfferToReceiveVideo", "true"));
    constraints.optional.add(new KeyValuePair("DtlsSrtpKeyAgreement", "true"));
    return constraints;
  }

  public void peerConnectionSetConfiguration(ConstraintsMap configuration,
      PeerConnection peerConnection) {
    if (peerConnection == null) {
      Log.d(TAG, "peerConnectionSetConfiguration() peerConnection is null");
      return;
    }
    peerConnection.setConfiguration(parseRTCConfiguration(configuration));
  }

  public void peerConnectionAddStream(final String streamId, final String id, Result result) {
    MediaStream mediaStream = localStreams.get(streamId);
    if (mediaStream == null) {
      Log.d(TAG, "peerConnectionAddStream() mediaStream is null");
      return;
    }
    PeerConnection peerConnection = getPeerConnection(id);
    if (peerConnection != null) {
      boolean res = peerConnection.addStream(mediaStream);
      Log.d(TAG, "addStream" + result);
      result.success(res);
    } else {
      Log.d(TAG, "peerConnectionAddStream() peerConnection is null");
      result.error("peerConnectionAddStreamFailed",
          "peerConnectionAddStream() peerConnection is null", null);
    }
  }

  public void peerConnectionRemoveStream(final String streamId, final String id, Result result) {
    MediaStream mediaStream = localStreams.get(streamId);
    if (mediaStream == null) {
      Log.d(TAG, "peerConnectionRemoveStream() mediaStream is null");
      return;
    }
    PeerConnection peerConnection = getPeerConnection(id);
    if (peerConnection != null) {
      peerConnection.removeStream(mediaStream);
      result.success(null);
    } else {
      Log.d(TAG, "peerConnectionRemoveStream() peerConnection is null");
      result.error("peerConnectionRemoveStreamFailed",
          "peerConnectionAddStream() peerConnection is null", null);
    }
  }

  public void peerConnectionCreateOffer(
      String id,
      ConstraintsMap constraints,
      final Result result) {
    PeerConnection peerConnection = getPeerConnection(id);

    if (peerConnection != null) {
      peerConnection.createOffer(new SdpObserver() {
        @Override
        public void onCreateFailure(String s) {
          result.error("WEBRTC_CREATE_OFFER_ERROR", s, null);
        }

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
          ConstraintsMap params = new ConstraintsMap();
          params.putString("sdp", sdp.description);
          params.putString("type", sdp.type.canonicalForm());
          result.success(params.toMap());
        }

        @Override
        public void onSetFailure(String s) {
        }

        @Override
        public void onSetSuccess() {
        }
      }, parseMediaConstraints(constraints));
    } else {
      Log.d(TAG, "peerConnectionCreateOffer() peerConnection is null");
      result.error("WEBRTC_CREATE_OFFER_ERROR", "peerConnection is null", null);
    }
  }

  public void peerConnectionCreateAnswer(
      String id,
      ConstraintsMap constraints,
      final Result result) {
    PeerConnection peerConnection = getPeerConnection(id);

    if (peerConnection != null) {
      peerConnection.createAnswer(new SdpObserver() {
        @Override
        public void onCreateFailure(String s) {
          result.error("WEBRTC_CREATE_ANSWER_ERROR", s, null);
        }

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
          ConstraintsMap params = new ConstraintsMap();
          params.putString("sdp", sdp.description);
          params.putString("type", sdp.type.canonicalForm());
          result.success(params.toMap());
        }

        @Override
        public void onSetFailure(String s) {
        }

        @Override
        public void onSetSuccess() {
        }
      }, parseMediaConstraints(constraints));
    } else {
      Log.d(TAG, "peerConnectionCreateAnswer() peerConnection is null");
      result.error("WEBRTC_CREATE_ANSWER_ERROR", "peerConnection is null", null);
    }
  }

  public void peerConnectionSetLocalDescription(ConstraintsMap sdpMap, final String id,
      final Result result) {
    PeerConnection peerConnection = getPeerConnection(id);

    Log.d(TAG, "peerConnectionSetLocalDescription() start");
    if (peerConnection != null) {
      SessionDescription sdp = new SessionDescription(
          Type.fromCanonicalForm(sdpMap.getString("type")),
          sdpMap.getString("sdp")
      );

      peerConnection.setLocalDescription(new SdpObserver() {
        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
          result.success(null);
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
          result.error("WEBRTC_SET_LOCAL_DESCRIPTION_ERROR", s, null);
        }
      }, sdp);
    } else {
      Log.d(TAG, "peerConnectionSetLocalDescription() peerConnection is null");
      result.error("WEBRTC_SET_LOCAL_DESCRIPTION_ERROR", "peerConnection is null", null);
    }
    Log.d(TAG, "peerConnectionSetLocalDescription() end");
  }

  public void peerConnectionSetRemoteDescription(final ConstraintsMap sdpMap, final String id,
      final Result result) {
    PeerConnection peerConnection = getPeerConnection(id);
    // final String d = sdpMap.getString("type");

    Log.d(TAG, "peerConnectionSetRemoteDescription() start");
    if (peerConnection != null) {
      SessionDescription sdp = new SessionDescription(
          Type.fromCanonicalForm(sdpMap.getString("type")),
          sdpMap.getString("sdp")
      );

      peerConnection.setRemoteDescription(new SdpObserver() {
        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
        }

        @Override
        public void onSetSuccess() {
          result.success(null);
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
          result.error("WEBRTC_SET_REMOTE_DESCRIPTION_ERROR", s, null);
        }
      }, sdp);
    } else {
      Log.d(TAG, "peerConnectionSetRemoteDescription() peerConnection is null");
      result.error("WEBRTC_SET_REMOTE_DESCRIPTION_ERROR", "peerConnection is null", null);
    }
    Log.d(TAG, "peerConnectionSetRemoteDescription() end");
  }

  public void peerConnectionAddICECandidate(ConstraintsMap candidateMap, final String id,
      final Result result) {
    boolean res = false;
    PeerConnection peerConnection = getPeerConnection(id);
    Log.d(TAG, "peerConnectionAddICECandidate() start");
    if (peerConnection != null) {
      IceCandidate candidate = new IceCandidate(
          candidateMap.getString("sdpMid"),
          candidateMap.getInt("sdpMLineIndex"),
          candidateMap.getString("candidate")
      );
      res = peerConnection.addIceCandidate(candidate);
    } else {
      Log.d(TAG, "peerConnectionAddICECandidate() peerConnection is null");
      result.error("peerConnectionAddICECandidateFailed",
          "peerConnectionAddICECandidate() peerConnection is null", null);
    }
    result.success(res);
    Log.d(TAG, "peerConnectionAddICECandidate() end");
  }

  public void peerConnectionGetStats(String trackId, String id, final Result result) {
    PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "peerConnectionGetStats() peerConnection is null");
    } else {
      pco.getStats(trackId, result);
    }
  }

  public void peerConnectionClose(final String id) {
    PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "peerConnectionClose() peerConnection is null");
    } else {
      pco.close();
    }
  }

  public void peerConnectionDispose(final String id) {
    PeerConnectionObserver pco = mPeerConnectionObservers.get(id);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "peerConnectionDispose() peerConnection is null");
    } else {
      pco.dispose();
      mPeerConnectionObservers.remove(id);
    }
    if (mPeerConnectionObservers.size() == 0) {
      audioManager.onAudioManagerRequested(false);
    }
  }

  public void mediaStreamRelease(final String id) {
    MediaStream mediaStream = localStreams.get(id);
    if (mediaStream != null) {
      for (VideoTrack track : mediaStream.videoTracks) {
        localTracks.remove(track.id());
        getUserMediaImpl.removeVideoCapturer(track.id());
      }
      for (AudioTrack track : mediaStream.audioTracks) {
        localTracks.remove(track.id());
      }
      localStreams.remove(id);
    } else {
      Log.d(TAG, "mediaStreamRelease() mediaStream is null");
    }
  }

  public void createDataChannel(final String peerConnectionId, String label, ConstraintsMap config,
      Result result) {
    // Forward to PeerConnectionObserver which deals with DataChannels
    // because DataChannel is owned by PeerConnection.
    PeerConnectionObserver pco
        = mPeerConnectionObservers.get(peerConnectionId);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "createDataChannel() peerConnection is null");
    } else {
      pco.createDataChannel(label, config, result);
    }
  }

  public void dataChannelSend(String peerConnectionId, int dataChannelId, ByteBuffer bytebuffer,
      Boolean isBinary) {
    // Forward to PeerConnectionObserver which deals with DataChannels
    // because DataChannel is owned by PeerConnection.
    PeerConnectionObserver pco
        = mPeerConnectionObservers.get(peerConnectionId);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "dataChannelSend() peerConnection is null");
    } else {
      pco.dataChannelSend(dataChannelId, bytebuffer, isBinary);
    }
  }

  public void dataChannelClose(String peerConnectionId, int dataChannelId) {
    // Forward to PeerConnectionObserver which deals with DataChannels
    // because DataChannel is owned by PeerConnection.
    PeerConnectionObserver pco
        = mPeerConnectionObservers.get(peerConnectionId);
    if (pco == null || pco.getPeerConnection() == null) {
      Log.d(TAG, "dataChannelClose() peerConnection is null");
    } else {
      pco.dataChannelClose(dataChannelId);
    }
  }

  public void setActivity(Activity activity) {
    this.activity = activity;
  }
}