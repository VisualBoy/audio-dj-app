package com.audiodj.capture

import android.content.Context
import android.media.projection.MediaProjection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WebRtcPublisher(
    private val context: Context,
    private val log: (String) -> Unit
) {
    private var factory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null

    init {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            val adm = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseStereoInput(true)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()

            factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            log("[webrtc] factory init failed: ${e.message}")
        }
    }

    fun connect(scope: CoroutineScope, backendUrl: String, deckId: String, projection: MediaProjection?) {
        scope.launch(Dispatchers.IO) {
            try {
                log("[webrtc] initializing peer connection for $deckId (hasProjection=${projection != null})")
                val pcf = factory ?: run {
                    log("[webrtc] PeerConnectionFactory is null")
                    return@launch
                }

                audioSource = pcf.createAudioSource(MediaConstraints())
                audioTrack = pcf.createAudioTrack("aux_audio_track_$deckId", audioSource)

                val rtcConfig = PeerConnection.RTCConfiguration(emptyList())

                val pcObserver = object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        log("[webrtc] ICE connection state: $state")
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        log("[webrtc] ICE gathering state: $state")
                        if (state == PeerConnection.IceGatheringState.COMPLETE) {
                            val localSdp = peerConnection?.localDescription?.description
                            if (localSdp != null) {
                                sendSdpOffer(scope, backendUrl, deckId, localSdp)
                            }
                        }
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) {}
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(dataChannel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                }

                peerConnection = pcf.createPeerConnection(rtcConfig, pcObserver)
                peerConnection?.addTrack(audioTrack, listOf("aux_stream_$deckId"))

                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp != null) {
                            peerConnection?.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    log("[webrtc] local description set successfully")
                                }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(err: String?) {
                                    log("[webrtc] setLocalDescription failed: $err")
                                }
                            }, sdp)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) {
                        log("[webrtc] createOffer failed: $err")
                    }
                    override fun onSetFailure(err: String?) {}
                }, constraints)

            } catch (e: Exception) {
                log("[webrtc] connect failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun sendSdpOffer(scope: CoroutineScope, backendUrl: String, deckId: String, offerSdp: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val fullUrl = "${backendUrl.trimEnd('/')}/api/sdp"
                log("[webrtc] sending SDP offer to $fullUrl for deck $deckId")
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val payload = JSONObject().apply {
                    put("client_type", "publisher")
                    put("deck_id", deckId)
                    put("sdp", offerSdp)
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    val respJson = JSONObject(body)
                    val answerSdp = respJson.getString("sdp")

                    withContext(Dispatchers.Main) {
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                log("[webrtc] remote SDP answer set successfully. Connected!")
                            }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(err: String?) {
                                log("[webrtc] setRemoteDescription failed: $err")
                            }
                        }, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
                    }
                } else {
                    log("[webrtc] SDP POST failed with response code $responseCode")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                log("[webrtc] SDP offer dispatch failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            peerConnection?.close()
            peerConnection = null
            audioTrack?.dispose()
            audioTrack = null
            audioSource?.dispose()
            audioSource = null
            log("[webrtc] disconnected")
        } catch (e: Exception) {
            log("[webrtc] disconnect error: ${e.message}")
        }
    }
}
