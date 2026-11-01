package com.bomba.alltogether;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import org.json.JSONObject;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.ScreenCapturerAndroid;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Shares this phone's screen with the Bomba Live wall.
 * Uses the same free PeerJS connection service as the web page, so computers and phones meet in one place.
 */
public class ScreenShareService extends Service {

    public static final String EXTRA_CODE = "code", EXTRA_NAME = "name", EXTRA_RESULT = "result", EXTRA_DATA = "data";
    private static final String CHANNEL = "bomba_share";
    private static final int NOTE_ID = 7;

    /** Lets the Bomba screen show live status. */
    public interface Listener { void onShareStatus(String json); }
    public static volatile Listener listener;
    private static volatile String lastStatus = "{}";
    public static String status() { return lastStatus; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket ws;
    private boolean stopping;
    private int retries;
    private String peerId, name;
    private int offers, answers;
    private String lastProblem;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private ScreenCapturerAndroid capturer;
    private SurfaceTextureHelper helper;
    private VideoSource source;
    private VideoTrack track;

    private static class Conn {
        PeerConnection pc; String peer; boolean remoteSet;
        final List<IceCandidate> pending = new ArrayList<>();
    }
    private final Map<String, Conn> conns = Collections.synchronizedMap(new HashMap<>());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        if (capturer != null) return START_NOT_STICKY;   // already sharing

        String code = intent.getStringExtra(EXTRA_CODE);
        name = intent.getStringExtra(EXTRA_NAME);
        int result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (code == null || name == null || data == null || result != Activity.RESULT_OK) { report("error", "Sharing was cancelled."); stopSelf(); return START_NOT_STICKY; }
        peerId = "bomba-" + code.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + slug(name);

        // Android 14 needs the foreground notice up before screen capture begins.
        Notification n = notice();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(NOTE_ID, n);

        report("starting", null);
        try {
            startCapture(data);
            connectSignaling();
        } catch (Exception e) {
            report("error", "Couldn’t start sharing: " + e.getMessage());
            shutdown();
        }
        return START_NOT_STICKY;
    }

    // ---------- capture ----------
    private void startCapture(Intent data) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions());
        egl = EglBase.create();
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
            .createPeerConnectionFactory();

        capturer = new ScreenCapturerAndroid(data, new MediaProjection.Callback() {
            @Override public void onStop() { main.post(ScreenShareService.this::shutdown); }
        });
        helper = SurfaceTextureHelper.create("BombaCapture", egl.getEglBaseContext());
        source = factory.createVideoSource(true);
        capturer.initialize(helper, getApplicationContext(), source.getCapturerObserver());

        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, h = dm.heightPixels;
        float scale = Math.min(1f, 1280f / Math.max(w, h));
        w = Math.round(w * scale / 2) * 2; h = Math.round(h * scale / 2) * 2;
        capturer.startCapture(w, h, 15);
        track = factory.createVideoTrack("bomba-screen", source);
        track.setEnabled(true);
    }

    // ---------- signaling (PeerJS protocol) ----------
    private void connectSignaling() {
        if (stopping) return;
        String token = Long.toString(Math.abs(new SecureRandom().nextLong()), 36);
        String url = "wss://0.peerjs.com:443/peerjs?key=peerjs&id=" + peerId + "&token=" + token + "&version=1.5.4";
        ws = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket s, String text) { main.post(() -> onSignal(text)); }
            @Override public void onFailure(WebSocket s, Throwable t, Response r) { main.post(() -> reconnect()); }
            @Override public void onClosed(WebSocket s, int code, String reason) { main.post(() -> reconnect()); }
        });
        main.removeCallbacks(heartbeat);
        main.postDelayed(heartbeat, 5000);
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (stopping || ws == null) return;
            ws.send("{\"type\":\"HEARTBEAT\"}");
            main.postDelayed(this, 5000);
        }
    };

    private void reconnect() {
        if (stopping) return;
        report("starting", null);
        retries++;
        main.postDelayed(this::connectSignaling, Math.min(15000, 1500L * retries));
    }

    private void onSignal(String text) {
        try {
            JSONObject m = new JSONObject(text);
            String type = m.optString("type"), src = m.optString("src");
            JSONObject payload = m.optJSONObject("payload");
            switch (type) {
                case "OPEN": retries = 0; report("live", null); break;
                case "ID-TAKEN": report("error", "Another screen is already sharing as " + name + ". Pick a different device name."); shutdown(); break;
                case "ERROR": report("error", payload != null ? payload.optString("msg", "Connection error") : "Connection error"); break;
                case "OFFER":
                    offers++;
                    if (payload != null) { try { answer(src, payload); } catch (Throwable t) { problem("offer: " + t); } }
                    break;
                case "CANDIDATE": if (payload != null) candidate(payload); break;
                case "LEAVE": case "EXPIRE": dropPeer(src); break;
                default:
            }
        } catch (Throwable t) { problem("signal: " + t); }
    }

    private void problem(String p) { lastProblem = p.length() > 140 ? p.substring(0, 140) : p; report("live", null); }

    private void send(JSONObject msg) { if (ws != null) ws.send(msg.toString()); }

    private void answer(String src, JSONObject payload) throws Exception {
        final String cid = payload.getString("connectionId");
        JSONObject sdpObj = payload.getJSONObject("sdp");
        Conn old = conns.remove(cid);
        if (old != null && old.pc != null) old.pc.close();

        final Conn c = new Conn();
        c.peer = src;
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        PeerConnection.RTCConfiguration cfg = new PeerConnection.RTCConfiguration(ice);
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        c.pc = factory.createPeerConnection(cfg, new PeerConnection.Observer() {
            @Override public void onIceCandidate(IceCandidate ic) {
                try {
                    JSONObject cand = new JSONObject().put("candidate", ic.sdp).put("sdpMid", ic.sdpMid).put("sdpMLineIndex", ic.sdpMLineIndex);
                    JSONObject p = new JSONObject().put("candidate", cand).put("type", "media").put("connectionId", cid);
                    JSONObject msg = new JSONObject().put("type", "CANDIDATE").put("payload", p).put("dst", src);
                    main.post(() -> send(msg));
                } catch (Exception ignored) { }
            }
            @Override public void onConnectionChange(PeerConnection.PeerConnectionState s) {
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) main.post(() -> drop(cid));
                else if (s == PeerConnection.PeerConnectionState.CONNECTED) main.post(() -> report("live", null));
            }
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { }
            @Override public void onIceConnectionReceivingChange(boolean b) { }
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { }
            @Override public void onIceCandidatesRemoved(IceCandidate[] c) { }
            @Override public void onAddStream(MediaStream s) { }
            @Override public void onRemoveStream(MediaStream s) { }
            @Override public void onDataChannel(org.webrtc.DataChannel d) { }
            @Override public void onRenegotiationNeeded() { }
            @Override public void onAddTrack(RtpReceiver r, MediaStream[] s) { }
        });
        if (c.pc == null) { problem("could not create connection"); return; }
        conns.put(cid, c);

        SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, sdpObj.getString("sdp"));
        c.pc.setRemoteDescription(new Sdp() {
            @Override public void onSetSuccess() {
                main.post(() -> {
                    synchronized (c.pending) { c.remoteSet = true; for (IceCandidate ic : c.pending) c.pc.addIceCandidate(ic); c.pending.clear(); }
                    // Send our screen back on the wall's video line.
                    boolean attached = false;
                    for (RtpTransceiver t : c.pc.getTransceivers()) {
                        if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) {
                            // the wall needs a stream id to show the video
                            try { t.getSender().setStreams(Collections.singletonList("bomba-screen")); } catch (Throwable e) { problem("streams: " + e); }
                            t.getSender().setTrack(track, false);
                            t.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
                            attached = true; break;
                        }
                    }
                    if (!attached) c.pc.addTrack(track, Collections.singletonList("bomba"));
                    c.pc.createAnswer(new Sdp() {
                        @Override public void onCreateSuccess(SessionDescription ans) {
                            c.pc.setLocalDescription(new Sdp() {
                                @Override public void onSetSuccess() {
                                    try {
                                        JSONObject sdp = new JSONObject().put("type", "answer").put("sdp", ans.description);
                                        JSONObject p = new JSONObject().put("sdp", sdp).put("type", "media").put("connectionId", cid).put("browser", "bomba-android");
                                        JSONObject msg = new JSONObject().put("type", "ANSWER").put("payload", p).put("dst", src);
                                        main.post(() -> { send(msg); answers++; report("live", null); });
                                    } catch (Exception ignored) { }
                                }
                                @Override public void onSetFailure(String e) { main.post(() -> problem("local: " + e)); }
                            }, ans);
                        }
                        @Override public void onCreateFailure(String e) { main.post(() -> problem("answer: " + e)); }
                    }, new MediaConstraints());
                });
            }
            @Override public void onSetFailure(String e) { main.post(() -> problem("remote: " + e)); }
        }, offer);
    }

    private void candidate(JSONObject payload) throws Exception {
        Conn c = conns.get(payload.optString("connectionId"));
        JSONObject j = payload.optJSONObject("candidate");
        if (c == null || j == null || c.pc == null) return;
        String cand = j.optString("candidate");
        if (cand.isEmpty()) return;
        IceCandidate ic = new IceCandidate(j.optString("sdpMid", "0"), j.optInt("sdpMLineIndex", 0), cand);
        synchronized (c.pending) { if (c.remoteSet) c.pc.addIceCandidate(ic); else c.pending.add(ic); }
    }

    private void drop(String cid) {
        Conn c = conns.remove(cid);
        if (c != null && c.pc != null) try { c.pc.close(); } catch (Exception ignored) { }
        report("live", null);
    }

    private void dropPeer(String peer) {
        List<String> ids = new ArrayList<>();
        synchronized (conns) { for (Map.Entry<String, Conn> e : conns.entrySet()) if (peer.equals(e.getValue().peer)) ids.add(e.getKey()); }
        for (String id : ids) drop(id);
    }

    // ---------- status, notice, shutdown ----------
    private void report(String state, String message) {
        try {
            JSONObject s = new JSONObject().put("state", state).put("name", name == null ? "" : name).put("viewers", conns.size())
                .put("offers", offers).put("answers", answers);
            if (lastProblem != null) s.put("problem", lastProblem);
            if (message != null) s.put("message", message);
            lastStatus = s.toString();
            Listener l = listener;
            if (l != null) l.onShareStatus(lastStatus);
        } catch (Exception ignored) { }
    }

    private Notification notice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ScreenShareService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Bomba is sharing your screen")
            .setContentText("Your Bomba Live wall can see this screen")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
            .build();
    }

    private void shutdown() {
        if (stopping) return;
        stopping = true;
        main.removeCallbacks(heartbeat);
        synchronized (conns) { for (Conn c : conns.values()) try { if (c.pc != null) c.pc.close(); } catch (Exception ignored) { } conns.clear(); }
        if (ws != null) try { ws.close(1000, "bye"); } catch (Exception ignored) { }
        if (capturer != null) { try { capturer.stopCapture(); } catch (Exception ignored) { } capturer.dispose(); capturer = null; }
        if (track != null) { track.dispose(); track = null; }
        if (source != null) { source.dispose(); source = null; }
        if (helper != null) { helper.dispose(); helper = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (egl != null) { egl.release(); egl = null; }
        lastStatus = "{\"state\":\"stopped\"}";
        Listener l = listener;
        if (l != null) l.onShareStatus(lastStatus);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { shutdown(); super.onDestroy(); }

    private static String slug(String s) { return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); }

    /** SdpObserver with empty defaults. */
    private abstract static class Sdp implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription d) { }
        @Override public void onSetSuccess() { }
        @Override public void onCreateFailure(String e) { }
        @Override public void onSetFailure(String e) { }
    }
}
