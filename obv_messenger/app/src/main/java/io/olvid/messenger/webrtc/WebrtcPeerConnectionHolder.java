/*
 *  Olvid for Android
 *  Copyright © 2019-2023 Olvid SAS
 *
 *  This file is part of Olvid for Android.
 *
 *  Olvid is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License, version 3,
 *  as published by the Free Software Foundation.
 *
 *  Olvid is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with Olvid.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.olvid.messenger.webrtc;


import androidx.annotation.NonNull;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.audio.AudioDeviceModule;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.olvid.engine.Logger;
import io.olvid.messenger.App;
import io.olvid.messenger.settings.SettingsActivity;
import io.olvid.messenger.webrtc.WebrtcCallService.FailReason;

class WebrtcPeerConnectionHolder {
    @NonNull
    private final WebrtcCallService webrtcCallService;
    @NonNull
    private final WebrtcCallService.CallParticipant callParticipant;

    private static final List<String> TURN_SCALED_SERVERS = Arrays.asList(
            "turn:turn-scaled.olvid.io:5349?transport=udp",
            "turn:turn-scaled.olvid.io:443?transport=tcp",
            "turns:turn-scaled.olvid.io:443?transport=tcp");
    private static final List<String> TURN_SCALED_SERVERS_EU = Arrays.asList(
            "turn:eu.turn-scaled.olvid.io:5349?transport=udp",
            "turn:eu.turn-scaled.olvid.io:443?transport=tcp",
            "turns:eu.turn-scaled.olvid.io:443?transport=tcp");
    private static final List<String> TURN_SCALED_SERVERS_US = Arrays.asList(
            "turn:us.turn-scaled.olvid.io:5349?transport=udp",
            "turn:us.turn-scaled.olvid.io:443?transport=tcp",
            "turns:us.turn-scaled.olvid.io:443?transport=tcp");
    private static final List<String> TURN_SCALED_SERVERS_AP = Arrays.asList(
            "turn:ap.turn-scaled.olvid.io:5349?transport=udp",
            "turn:ap.turn-scaled.olvid.io:443?transport=tcp",
            "turns:ap.turn-scaled.olvid.io:443?transport=tcp");

    private static final Set<String> AUDIO_CODECS = new HashSet<>(Arrays.asList("opus", "PCMU", "PCMA", "telephone-event", "red"));
    private static final String ADDITIONAL_OPUS_OPTIONS = ";cbr=1"; // by default send and receive are mono, no need to add "stereo=0;sprop-stereo=0"

    static EglBase eglBase;
    static PeerConnectionFactory peerConnectionFactory;
    static AudioDeviceModule audioDeviceModule;

    final PeerConnectionObserver peerConnectionObserver = new PeerConnectionObserver();
    final SessionDescriptionObserver sessionDescriptionObserver = new SessionDescriptionObserver();

    private String peerSessionDescriptionType;
    private String peerSessionDescription;

    private int reconnectOfferCounter;
    private int reconnectAnswerCounter;

    private String turnUsername;
    private String turnPassword;

    private WebrtcCallService.GatheringPolicy gatheringPolicy;
    private boolean readyToProcessPeerIceCandidates;
    private final List<WebrtcCallService.JsonIceCandidate> pendingPeerIceCandidates;

    PeerConnection peerConnection = null;
    AudioSource audioSource;
    AudioTrack audioTrack;
    DataChannel dataChannel;
    DataChannelMessageListener dataChannelMessageListener;

    public WebrtcPeerConnectionHolder(@NonNull WebrtcCallService webrtcCallService, @NonNull WebrtcCallService.CallParticipant callParticipant) {
        this.webrtcCallService = webrtcCallService;
        this.callParticipant = callParticipant;
        this.reconnectOfferCounter = 0;
        this.reconnectAnswerCounter = 0;
        this.gatheringPolicy = callParticipant.getGatheringPolicy();
        this.readyToProcessPeerIceCandidates = false;
        this.pendingPeerIceCandidates = new ArrayList<>();
    }

    public static void initializePeerConnectionFactory() {
        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();

        audioDeviceModule = JavaAudioDeviceModule.builder(App.getContext())
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule();

//        WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
//        WebRtcAudioUtils.setWebRtcBasedNoiseSuppressor(true);
//        WebRtcAudioUtils.setWebRtcBasedAutomaticGainControl(true);

//        eglBase = EglBase.create();
//        VideoEncoderFactory videoEncoderFactory = new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
//        VideoDecoderFactory videoDecoderFactory = new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(App.getContext())
                .createInitializationOptions());

//        org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO);

        PeerConnectionFactory.Builder builder = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(audioDeviceModule);
//                .setVideoEncoderFactory(videoEncoderFactory)
//                .setVideoDecoderFactory(videoDecoderFactory)

        // check if a proxy is required for WebRCT
        try {
            List<Proxy> proxies = ProxySelector.getDefault().select(URI.create("https://turn-scaled.olvid.io/"));
            for (Proxy proxy : proxies) {
                Proxy.Type type = proxy.type();
                if (type == Proxy.Type.HTTP) {
                    SocketAddress address = proxy.address();
                    if (address instanceof InetSocketAddress) {
                        builder.setHttpsProxy(((InetSocketAddress) address).getHostString(), ((InetSocketAddress) address).getPort());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        String userAgentProperty = System.getProperty("http.agent");
        if (userAgentProperty != null) {
            builder.setUserAgent(userAgentProperty);
        }
        peerConnectionFactory = builder.createPeerConnectionFactory();
    }


    void setAudioEnabled(boolean enabled) {
        if (audioTrack != null) {
            audioTrack.setEnabled(enabled);
        }
    }

    void setTurnCredentials(String turnUsername, String turnPassword) {
        this.turnUsername = turnUsername;
        this.turnPassword = turnPassword;
    }

    public void setPeerSessionDescription(String sessionDescriptionType, String sessionDescription) {
        if (peerConnection == null) {
            Logger.d("☎ Setting peer sdp\n" + sessionDescription);
            this.peerSessionDescriptionType = sessionDescriptionType;
            this.peerSessionDescription = sessionDescription;
        } else {
            Logger.d("☎ Setting remote description:\n" + sessionDescription);
            peerConnection.setRemoteDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.fromCanonicalForm(sessionDescriptionType), sessionDescription));

            readyToProcessPeerIceCandidates = true;
            for (WebrtcCallService.JsonIceCandidate jsonIceCandidate : pendingPeerIceCandidates) {
                peerConnection.addIceCandidate(new IceCandidate(jsonIceCandidate.sdpMid, jsonIceCandidate.sdpMLineIndex, jsonIceCandidate.sdp));
            }
            pendingPeerIceCandidates.clear();
        }
    }

    public void setGatheringPolicy(WebrtcCallService.GatheringPolicy gatheringPolicy) {
        this.gatheringPolicy = gatheringPolicy;
    }

    public void setDataChannelMessageListener(DataChannelMessageListener dataChannelMessageListener) {
        this.dataChannelMessageListener = dataChannelMessageListener;
    }

    public void sendDataChannelMessage(String message) {
        if (dataChannel != null) {
            dataChannel.send(new DataChannel.Buffer(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)), false));
        }
    }

    private void createAudioTrack(MediaConstraints mediaConstraints) {
        audioSource = peerConnectionFactory.createAudioSource(mediaConstraints);
        audioTrack = peerConnectionFactory.createAudioTrack("audio0", audioSource);
        audioTrack.setEnabled(!webrtcCallService.microphoneMuted);
        peerConnection.addTrack(audioTrack);
    }

    private void createDataChannel() {
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        init.negotiated = true;
        init.id = 1;
        dataChannel = peerConnection.createDataChannel("data0", init);
        DataChannel.Observer dataChannelObserver = new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {
            }

            @Override
            public void onStateChange() {
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    if (dataChannelMessageListener != null) {
                        dataChannelMessageListener.onConnect();
                    }
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (dataChannelMessageListener != null) {
                    dataChannelMessageListener.onMessage(buffer.data);
                }
            }
        };
        dataChannel.registerObserver(dataChannelObserver);
    }

    private static PeerConnection.IceServer getIceServer(String username, String password) {
        List<String> servers;
        if (SettingsActivity.getScaledTurn() != null) {
            switch (SettingsActivity.getScaledTurn()) {
                case "par":
                    servers = TURN_SCALED_SERVERS_EU;
                    break;
                case "nyc":
                    servers = TURN_SCALED_SERVERS_US;
                    break;
                case "sng":
                    servers = TURN_SCALED_SERVERS_AP;
                    break;
                case "global":
                default:
                    servers = TURN_SCALED_SERVERS;
                    break;
            }
        } else {
            servers = TURN_SCALED_SERVERS;
        }
        return PeerConnection.IceServer.builder(servers)
                .setUsername(username)
                .setPassword(password)
                // Well, let's encrypt root is not correctly recognized by libwebrtc and there is no way to override the root CAs, so we disable certificate check...
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_INSECURE_NO_CHECK)
                .createIceServer();
    }


    void createPeerConnection() {
        PeerConnection.IceServer iceServer = getIceServer(turnUsername, turnPassword);
        if (iceServer == null) {
            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.ICE_SERVER_CREDENTIALS_CREATION_ERROR);
            return;
        }
        PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(Collections.singletonList(iceServer));
        configuration.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
        configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        configuration.continualGatheringPolicy = (this.gatheringPolicy == WebrtcCallService.GatheringPolicy.GATHER_CONTINUOUSLY) ? PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY : PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;

        Logger.d("☎ Creating PeerConnection with GatheringPolicy: " + this.gatheringPolicy);
        peerConnection = peerConnectionFactory.createPeerConnection(configuration, peerConnectionObserver);
        if (peerConnection != null) {
            createAudioTrack(new MediaConstraints());
            createDataChannel();

            if (peerSessionDescription != null && peerSessionDescriptionType != null) {
                Logger.d("☎ Setting remote description:\n" + peerSessionDescription);
                peerConnection.setRemoteDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.fromCanonicalForm(peerSessionDescriptionType), peerSessionDescription));
                peerSessionDescription = null;
                peerSessionDescriptionType = null;

                readyToProcessPeerIceCandidates = true;
                for (WebrtcCallService.JsonIceCandidate jsonIceCandidate : pendingPeerIceCandidates) {
                    peerConnection.addIceCandidate(new IceCandidate(jsonIceCandidate.sdpMid, jsonIceCandidate.sdpMLineIndex, jsonIceCandidate.sdp));
                }
                pendingPeerIceCandidates.clear();
            }
        }
    }


    void createLocalDescription() {
        // only called from onRenegotiationNeeded
        if (peerConnection != null) {
            switch (peerConnection.signalingState()) {
                case STABLE:
                    reconnectOfferCounter++;
                    peerConnection.createOffer(sessionDescriptionObserver, new MediaConstraints());
                    break;
                case HAVE_REMOTE_OFFER:
                    peerConnection.createAnswer(sessionDescriptionObserver, new MediaConstraints());
                    break;
                case HAVE_LOCAL_OFFER:
                case HAVE_LOCAL_PRANSWER:
                case HAVE_REMOTE_PRANSWER:
                case CLOSED:
                    // do nothing
                    break;
            }
        }
    }


    void restartIce() {
        if (peerConnection != null) {
            if (peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                // rollback to a stable set before creating the new restart offer
                peerConnection.setLocalDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
            } else if (peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                // we received a remote offer
                // if we are the offer sender, rollback and send a new offer, otherwise juste wait for the answer process to finish
                if (webrtcCallService.shouldISendTheOfferToCallParticipant(callParticipant)) {
                    peerConnection.setLocalDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                } else {
                    return;
                }
            }

            peerConnection.restartIce();
        }
    }

    //////
    // when reading this, have a look at
    // https://w3c.github.io/webrtc-pc/#rtcsignalingstate-enum
    void handleReceivedRestartSdp(String sessionDescriptionType, String sessionDescription, int reconnectCounter, int peerReconnectCounterToOverride) {
        if (peerConnection != null) {
            switch (sessionDescriptionType) {
                case "offer": {
                    if (reconnectCounter < reconnectAnswerCounter) {
                        Logger.i("☎ Received restart offer with counter too low " + reconnectCounter + " vs. " + reconnectAnswerCounter);
                        break;
                    }

                    if (peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                        Logger.d("☎ Received restart offer while already having one --> rollback");
                        // rollback to a stable set before handling the new restart offer
                        peerConnection.setLocalDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                    } else if (peerConnection.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        // we already sent an offer
                        // if we are the offer sender, do nothing, otherwise rollback and process the new offer
                        if (webrtcCallService.shouldISendTheOfferToCallParticipant(callParticipant)) {
                            if (peerReconnectCounterToOverride == reconnectOfferCounter) {
                                Logger.d("☎ Received restart offer while already having created an offer. It specifies to override my current offer --> rollback");
                                peerConnection.setLocalDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                            } else {
                                Logger.d("☎ Received restart offer while already having created an offer. I am the offerer --> ignore this new offer");
                                break;
                            }
                        } else {
                            Logger.d("☎ Received restart offer while already having created an offer. I am not the offerer --> rollback");
                            peerConnection.setLocalDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.ROLLBACK, ""));
                        }
                    }

                    reconnectAnswerCounter = reconnectCounter;
                    Logger.d("☎ Setting remote description:\n" + sessionDescription);
                    peerConnection.setRemoteDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.fromCanonicalForm(sessionDescriptionType), sessionDescription));

                    peerConnection.restartIce();
                    break;
                }
                case "answer": {
                    if (reconnectCounter != reconnectOfferCounter) {
                        Logger.i("☎ Received restart answer with bad counter " + reconnectCounter + " vs. " + reconnectOfferCounter);
                        break;
                    }

                    if (peerConnection.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                        Logger.d("☎ Received restart answer while not in the HAVE_LOCAL_OFFER state --> ignore this restart answer");
                        break;
                    }

                    Logger.d("☎ Applying received restart answer");
                    Logger.d("☎ Setting remote description:\n" + sessionDescription);
                    peerConnection.setRemoteDescription(sessionDescriptionObserver, new SessionDescription(SessionDescription.Type.fromCanonicalForm(sessionDescriptionType), sessionDescription));
                    break;
                }
            }
        }
    }



    private boolean iceGatheringCompletedCalled;
    synchronized private void iceGatheringCompleted() {
        if (!iceGatheringCompletedCalled && peerConnection != null) {
            iceGatheringCompletedCalled = true;
            SessionDescription sdp = peerConnection.getLocalDescription();
            // We no longer need to filter out non-relay connections manually
            //  String filteredDescription = filterSdpDescriptionKeepOnlyRelay(sdp.description);

            if (sdp.type == SessionDescription.Type.OFFER) {
                webrtcCallService.sendLocalDescriptionToPeer(callParticipant, sdp.type.canonicalForm(), sdp.description, reconnectOfferCounter, reconnectAnswerCounter);
            } else {
                webrtcCallService.sendLocalDescriptionToPeer(callParticipant, sdp.type.canonicalForm(), sdp.description, reconnectAnswerCounter, -1);
            }
        }
    }

    void addIceCandidates(Collection<WebrtcCallService.JsonIceCandidate> jsonIceCandidates) {
        if (readyToProcessPeerIceCandidates) {
            if (peerConnection == null || gatheringPolicy != WebrtcCallService.GatheringPolicy.GATHER_CONTINUOUSLY) {
                return;
            }
            for (WebrtcCallService.JsonIceCandidate jsonIceCandidate: jsonIceCandidates) {
                peerConnection.addIceCandidate(new IceCandidate(jsonIceCandidate.sdpMid, jsonIceCandidate.sdpMLineIndex, jsonIceCandidate.sdp));
            }
        } else {
            pendingPeerIceCandidates.addAll(jsonIceCandidates);
        }
    }

    void removeIceCandidates(WebrtcCallService.JsonIceCandidate[] jsonIceCandidates) {
        if (readyToProcessPeerIceCandidates) {
            if (peerConnection == null || gatheringPolicy != WebrtcCallService.GatheringPolicy.GATHER_CONTINUOUSLY) {
                return;
            }
            IceCandidate[] iceCandidates = new IceCandidate[jsonIceCandidates.length];
            int i=0;
            for (WebrtcCallService.JsonIceCandidate jsonIceCandidate: jsonIceCandidates) {
                iceCandidates[i] = new IceCandidate(jsonIceCandidate.sdpMid, jsonIceCandidate.sdpMLineIndex, jsonIceCandidate.sdp);
                i++;
            }
            peerConnection.removeIceCandidates(iceCandidates);
        } else {
            pendingPeerIceCandidates.removeAll(Arrays.asList(jsonIceCandidates));
        }
    }


    private String filterSdpDescriptionCodec(String description) {
        Pattern mediaStartAudio = Pattern.compile("^m=audio\\s+");
        Pattern mediaStart = Pattern.compile("^m=");
        BufferedReader br = new BufferedReader(new StringReader(description));
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            boolean audioStarted = false;
            ArrayList<String> audioLines = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                if (audioStarted) {
                    Matcher m = mediaStart.matcher(line);
                    if (m.find()) {
                        audioStarted = false;
                        processAudioLines(sb, audioLines);
                        sb.append(line); sb.append("\n");
                    } else {
                        audioLines.add(line);
                    }
                } else {
                    Matcher m = mediaStartAudio.matcher(line);
                    if (m.find()) {
                        audioStarted = true;
                        audioLines.add(line);
                    } else {
                        sb.append(line); sb.append("\n");
                    }
                }
            }
            if (audioStarted) {
                processAudioLines(sb, audioLines);
            }
        } catch (Exception e) {
            e.printStackTrace();
            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.INTERNAL_ERROR);
            return "";
        }
        return sb.toString();
    }

    private void processAudioLines(StringBuilder sb, ArrayList<String> audioLines) throws Exception {
        Pattern rtpmapPattern = Pattern.compile("^a=rtpmap:([0-9]+)\\s+([^\\s/]+)");
        Set<String> formatsToKeep = new HashSet<>();
        String opusFormat = null;
        for (String line: audioLines) {
            Matcher m = rtpmapPattern.matcher(line);
            if (m.find()) {
                String codec = m.group(2);
                if (AUDIO_CODECS.contains(codec)) {
                    formatsToKeep.add(m.group(1));
                    if ("opus".equals(codec)) {
                        opusFormat = m.group(1);
                    }
                }
            }
        }
        // rewrite the first line
        {
            Pattern firstLine = Pattern.compile("^(m=\\S+\\s+\\S+\\s+\\S+)\\s+(([0-9]+\\s*)+)$");
            Matcher m = firstLine.matcher(audioLines.get(0));
            if (!m.find()) {
                throw new Exception();
            }
            sb.append(m.group(1));
            //noinspection ConstantConditions
            for (String fmt : m.group(2).split("\\s")) {
                if (formatsToKeep.contains(fmt)) {
                    sb.append(" "); sb.append(fmt);
                }
            }
            sb.append("\n");
        }

        // filter subsequent lines
        Pattern rtpmapOrOptionPattern = Pattern.compile("^a=(rtpmap|fmtp|rtcp-fb):([0-9]+)\\s+");
        for (int i=1; i<audioLines.size(); i++) {
            String line = audioLines.get(i);
            Matcher m = rtpmapOrOptionPattern.matcher(line);
            if (!m.find()) {
                sb.append(line); sb.append("\n");
            } else {
                String lineType = m.group(1);
                String fmt = m.group(2);
                if (formatsToKeep.contains(fmt)) {
                    if (opusFormat != null && opusFormat.equals(fmt) && "ftmp".equals(lineType)) {
                        sb.append(line);
                        sb.append(ADDITIONAL_OPUS_OPTIONS);
                        if (SettingsActivity.useLowBandwidthInCalls()) {
                            sb.append(";maxaveragebitrate=16000");
                        } else {
                            sb.append(";maxaveragebitrate=32000");
                        }
                    } else {
                        sb.append(line);
                    }
                    sb.append("\n");
                }
            }
        }
    }

    public void cleanUp() {
        if (peerConnection != null) {
            peerConnection.dispose();
            peerConnection = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (audioTrack != null) {
            audioTrack.dispose();
            audioTrack = null;
        }
        if (dataChannel != null) {
            dataChannel.dispose();
            dataChannel = null;
        }
    }

    public static void globalCleanup() {
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (audioDeviceModule != null) {
            audioDeviceModule.release();
            audioDeviceModule = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    private class PeerConnectionObserver implements PeerConnection.Observer {
        private int turnCandidates = 0;
        private PeerConnection.IceConnectionState connectionState;

        void resetGatheringState() {
            turnCandidates = 0;
            iceGatheringCompletedCalled = false;
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
            if (newState == PeerConnection.SignalingState.STABLE && connectionState == PeerConnection.IceConnectionState.CONNECTED) {
                // we reach a stable state while begin connected --> any ongoing reconnection is finished
                webrtcCallService.peerConnectionConnected(callParticipant);
            }
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState connectionState) {
            this.connectionState = connectionState;
            Logger.d("☎ onIceConnectionChange " + connectionState);
            switch (connectionState) {
                case NEW:
                case CHECKING:
                case COMPLETED:
                case CLOSED:
                    break;
                case CONNECTED: {
                    webrtcCallService.peerConnectionConnected(callParticipant);
                    break;
                }
                case DISCONNECTED:
                case FAILED: {
                    Logger.d("☎ Calling reconnectAfterConnectionLoss because of ICE DISCONNECT/FAIL");
                    webrtcCallService.reconnectAfterConnectionLoss(callParticipant);
                    break;
                }
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Logger.d("☎ onIceConnectionReceivingChange");
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Logger.d("☎ onIceGatheringChange " + newState.toString());
            switch (newState) {
                case NEW:
                    break;
                case GATHERING: {
                    // we start gathering --> clear the turnCandidates list
                    resetGatheringState();
                    // if gathering continuously, start a timer to detect potentially invalid credentials
                    if (gatheringPolicy == WebrtcCallService.GatheringPolicy.GATHER_CONTINUOUSLY && webrtcCallService.isCaller()) {
                        App.runThread(() -> {
                            // wait 5 seconds, then check if some candidates were found.
                            // --> there might be false positives, but this is not a big deal, worst case, we clear the cache...
                            try {
                                Thread.sleep(5_000);
                            } catch (InterruptedException e) {
                                return;
                            }
                            if (turnCandidates == 0) {
                                webrtcCallService.clearCredentialsCache();
                            }
                        });
                    }
                    break;
                }
                case COMPLETE: {
                    if (gatheringPolicy == WebrtcCallService.GatheringPolicy.GATHER_ONCE) {
                        if (turnCandidates == 0 && connectionState == null) {
                            Logger.w("☎ No TURN candidate found");
                            webrtcCallService.clearCredentialsCache();
                            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.SERVER_UNREACHABLE);
                        } else {
                            iceGatheringCompleted();
                        }
                    }
                    break;
                }
            }
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Logger.d("☎ onIceCandidate");
            switch (gatheringPolicy) {
                case GATHER_ONCE: {
                    if (!"".equals(candidate.serverUrl)) {
                        turnCandidates++;
                        if (turnCandidates == 1) {
                            App.runThread(() -> {
                                try {
                                    Thread.sleep(2000);
                                } catch (InterruptedException e) {
                                    // Nothing special to do
                                }
                                iceGatheringCompleted();
                            });
                        }
                    }
                    break;
                }
                case GATHER_CONTINUOUSLY: {
                    if (!"".equals(candidate.serverUrl)) {
                        turnCandidates++;
                        webrtcCallService.sendAddIceCandidateMessage(callParticipant, new WebrtcCallService.JsonIceCandidate(candidate.sdp, candidate.sdpMLineIndex, candidate.sdpMid));
                    }
                    break;
                }
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Logger.d("☎ onIceCandidatesRemoved");
            switch (gatheringPolicy) {
                case GATHER_ONCE:
                    break;
                case GATHER_CONTINUOUSLY: {
                    WebrtcCallService.JsonIceCandidate[] jsonIceCandidates = new WebrtcCallService.JsonIceCandidate[candidates.length];
                    for (int i = 0; i < candidates.length; i++) {
                        jsonIceCandidates[i] = new WebrtcCallService.JsonIceCandidate(candidates[i].sdp, candidates[i].sdpMLineIndex, candidates[i].sdpMid);
                    }
                    webrtcCallService.sendRemoveIceCandidatesMessage(callParticipant, jsonIceCandidates);
                    break;
                }
            }
        }

        @Override
        public void onAddStream(MediaStream stream) {
            Logger.d("☎ onAddStream");
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
            Logger.d("☎ onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
            Logger.d("☎ onDataChannel");
        }

        @Override
        public void onRenegotiationNeeded() {
            // called whenever a peerConnection is created, or after a restartICE. May not be called if a negotiation is already in progress
            webrtcCallService.synchronizeOnExecutor(WebrtcPeerConnectionHolder.this::createLocalDescription);
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {
            Logger.d("☎ onAddTrack");
        }
    }

    private abstract class SdpSetObserver implements SdpObserver {
        @Override
        public void onSetFailure(String error) {
            Logger.w("☎ onSetFailure " + error);
            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.PEER_CONNECTION_CREATION_ERROR);
        }

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {}

        @Override
        public void onCreateFailure(String s) {}
    }

    private class SessionDescriptionObserver implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sdp) {
            if (peerConnection.signalingState() != PeerConnection.SignalingState.STABLE &&
                    peerConnection.signalingState() != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                // if we are neither in stable nor in have_remote_offer, we shouldn't be creating an offer or an answer
                // --> we don't set anything
                return;
            }
            Logger.d("☎ onCreateSuccess. Filtering codecs.");
            String filteredSdpDescription = filterSdpDescriptionCodec(sdp.description);
            Logger.d("☎ Setting filtered local description:\n" + filteredSdpDescription);
            switch (gatheringPolicy) {
                case GATHER_ONCE:
                    peerConnectionObserver.resetGatheringState();
                    peerConnection.setLocalDescription(this, new SessionDescription(sdp.type, filteredSdpDescription));
                    break;
                case GATHER_CONTINUOUSLY:
                    peerConnection.setLocalDescription(new SdpSetObserver() {
                        @Override
                        public void onSetSuccess() {
                            Logger.d("☎ onSetSuccess");
                            if (sdp.type == SessionDescription.Type.OFFER) {
                                webrtcCallService.sendLocalDescriptionToPeer(callParticipant, sdp.type.canonicalForm(), filteredSdpDescription, reconnectOfferCounter, reconnectAnswerCounter);
                            } else {
                                webrtcCallService.sendLocalDescriptionToPeer(callParticipant, sdp.type.canonicalForm(), filteredSdpDescription, reconnectAnswerCounter, -1);
                            }
                        }
                    }, new SessionDescription(sdp.type, filteredSdpDescription));
                    break;
            }
        }

        @Override
        public void onSetSuccess() {
            // called when local or remote description are set
            // This automatically triggers ICE gathering or connection establishment --> nothing to do for GATHER_ONCE
        }

        @Override
        public void onCreateFailure(String error) {
            Logger.w("☎ onCreateFailure " + error);
            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.PEER_CONNECTION_CREATION_ERROR);
        }

        @Override
        public void onSetFailure(String error) {
            Logger.w("☎ onSetFailure " + error);
            webrtcCallService.peerConnectionHolderFailed(callParticipant, FailReason.PEER_CONNECTION_CREATION_ERROR);
        }
    }

    interface DataChannelMessageListener {
        void onConnect();
        void onMessage(ByteBuffer byteBuffer);
    }
}
