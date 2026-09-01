const backendUrlInput = document.getElementById('backendUrlInput') as HTMLInputElement
const connectBtn = document.getElementById('connectBtn') as HTMLButtonElement
const disconnectBtn = document.getElementById('disconnectBtn') as HTMLButtonElement
const startAudioBtn = document.getElementById('startAudioBtn') as HTMLButtonElement
const statusEl = document.getElementById('status')!
const detailEl = document.getElementById('detail')!

// DJ Controls
const targetDeckSelect = document.getElementById('targetDeckSelect') as HTMLSelectElement
const crossfaderInput = document.getElementById('crossfaderInput') as HTMLInputElement
const crossfaderVal = document.getElementById('crossfaderVal')!
const eqLowInput = document.getElementById('eqLowInput') as HTMLInputElement
const eqLowVal = document.getElementById('eqLowVal')!
const eqMidInput = document.getElementById('eqMidInput') as HTMLInputElement
const eqMidVal = document.getElementById('eqMidVal')!
const eqHighInput = document.getElementById('eqHighInput') as HTMLInputElement
const eqHighVal = document.getElementById('eqHighVal')!
const gainInput = document.getElementById('gainInput') as HTMLInputElement
const gainVal = document.getElementById('gainVal')!
const tempoInput = document.getElementById('tempoInput') as HTMLInputElement
const tempoVal = document.getElementById('tempoVal')!

// Load saved backend URL
const savedUrl = localStorage.getItem('webrtc_backend_url')
if (savedUrl) {
  backendUrlInput.value = savedUrl
}

let pc: RTCPeerConnection | null = null
let dc: RTCDataChannel | null = null
let audioCtx: AudioContext | null = null
let analyser: AnalyserNode | null = null
let remoteAudioEl: HTMLAudioElement | null = null
let remoteRmsDb = -120

function report(extra: Record<string, unknown> = {}) {
  const info = {
    iceConnectionState: pc?.iceConnectionState ?? 'null',
    iceGatheringState: pc?.iceGatheringState ?? 'null',
    signalingState: pc?.signalingState ?? 'null',
    dataChannelState: dc?.readyState ?? 'null',
    remoteRmsDb: Math.round(remoteRmsDb),
    ts: performance.now() | 0,
    ...extra,
  }
  ;(window as any).__masterState = info
  statusEl.textContent = pc ? pc.iceConnectionState : 'Disconnected'
  statusEl.className =
    pc?.iceConnectionState === 'connected'
      ? 'status-connected'
      : pc?.iceConnectionState === 'failed'
      ? 'status-error'
      : 'status-disconnected'
  detailEl.textContent = JSON.stringify(info, null, 2)
  console.log('[master]', JSON.stringify(info))
}

async function connect() {
  const backendUrl = backendUrlInput.value.trim().replace(/\/+$/, '')
  localStorage.setItem('webrtc_backend_url', backendUrl)

  report({ note: 'initiating connection', backendUrl })

  try {
    pc = new RTCPeerConnection()

    // Listen to connection changes
    pc.oniceconnectionstatechange = () => report()
    pc.onsignalingstatechange = () => report()

    // Add audio recvonly transceiver
    pc.addTransceiver('audio', { direction: 'recvonly' })

    // Create DataChannel for DJ controls
    dc = pc.createDataChannel('dj-controls', { ordered: false, maxRetransmits: 0 })
    dc.onopen = () => {
      report({ note: 'dataChannel dj-controls open' })
      // Send initial control settings
      emitCrossfade()
      emitEq()
      emitGain()
      emitTempo()
    }
    dc.onclose = () => report({ note: 'dataChannel dj-controls closed' })
    dc.onerror = (e) => report({ note: 'dataChannel error', error: String(e) })

    // Audio track handler
    pc.ontrack = (event) => {
      report({ note: 'ontrack event received', kind: event.track.kind })
      if (event.track.kind === 'audio') {
        if (!remoteAudioEl) {
          remoteAudioEl = document.createElement('audio')
          remoteAudioEl.autoplay = true
          document.body.appendChild(remoteAudioEl)
        }
        remoteAudioEl.srcObject = event.streams[0] || new MediaStream([event.track])
        try {
          audioCtx = audioCtx || new AudioContext()
          const src = audioCtx.createMediaStreamSource(remoteAudioEl.srcObject)
          analyser = audioCtx.createAnalyser()
          analyser.fftSize = 2048
          src.connect(analyser)
        } catch (e) {
          console.error('[master] analyser setup failed:', e)
        }
        report({ note: 'audio track attached and playout routed' })
      }
    }

    // Generate SDP Offer
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)

    report({ note: 'waiting for local ICE candidate gathering to complete...' })

    // Vanilla ICE: Wait until iceGatheringState === 'complete'
    if (pc.iceGatheringState !== 'complete') {
      await new Promise<void>((resolve) => {
        const check = () => {
          if (pc?.iceGatheringState === 'complete') {
            pc.removeEventListener('icegatheringstatechange', check)
            resolve()
          }
        }
        pc.addEventListener('icegatheringstatechange', check)
      })
    }

    report({ note: 'ICE gathering complete, sending SDP offer' })

    const offerPayload = {
      client_type: 'subscriber',
      deck_id: 'master',
      sdp: pc.localDescription!.sdp,
    }

    const res = await fetch(`${backendUrl}/api/sdp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(offerPayload),
    })

    if (!res.ok) {
      throw new Error(`SDP HTTP request failed with status ${res.status}`)
    }

    const answerJson = await res.json()
    const answerSdp = answerJson.sdp

    await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp })

    connectBtn.disabled = true
    disconnectBtn.disabled = false
    report({ note: 'SDP answer set, peer connection established' })
  } catch (e) {
    report({ error: String(e) })
    disconnect()
  }
}

function disconnect() {
  if (dc) {
    dc.close()
    dc = null
  }
  if (pc) {
    pc.close()
    pc = null
  }
  if (remoteAudioEl) {
    remoteAudioEl.srcObject = null
  }
  analyser = null
  remoteRmsDb = -120
  connectBtn.disabled = false
  disconnectBtn.disabled = true
  report({ note: 'disconnected' })
}

// Control payload emitter
function sendControlPayload(action: string, params: Record<string, any>) {
  const target = targetDeckSelect.value
  const payload = {
    type: 'control',
    action,
    target,
    params,
  }

  if (dc && dc.readyState === 'open') {
    dc.send(JSON.stringify(payload))
    console.log('[control sent]', payload)
  } else {
    console.warn('[control queued/skipped - dataChannel not open]', payload)
  }
}

function emitCrossfade() {
  const val = parseFloat(crossfaderInput.value)
  crossfaderVal.textContent = val.toFixed(2)
  sendControlPayload('set_crossfade', { crossfade: val })
}

function emitEq() {
  const low = parseFloat(eqLowInput.value)
  const mid = parseFloat(eqMidInput.value)
  const high = parseFloat(eqHighInput.value)
  eqLowVal.textContent = `${low.toFixed(1)} dB`
  eqMidVal.textContent = `${mid.toFixed(1)} dB`
  eqHighVal.textContent = `${high.toFixed(1)} dB`
  sendControlPayload('set_eq', {
    eq: { low, mid, high },
  })
}

function emitGain() {
  const val = parseFloat(gainInput.value)
  gainVal.textContent = val.toFixed(2)
  sendControlPayload('set_gain', { gain: val })
}

function emitTempo() {
  const val = parseFloat(tempoInput.value)
  tempoVal.textContent = `${val.toFixed(2)}x`
  sendControlPayload('set_tempo', { tempo: val })
}

// UI Event Listeners
connectBtn.addEventListener('click', connect)
disconnectBtn.addEventListener('click', disconnect)
startAudioBtn.addEventListener('click', () => {
  audioCtx?.resume()
  if (remoteAudioEl) {
    remoteAudioEl.play().catch(() => {})
  }
  report({ note: 'audioCtx resumed / play triggered' })
})

crossfaderInput.addEventListener('input', emitCrossfade)
eqLowInput.addEventListener('input', emitEq)
eqMidInput.addEventListener('input', emitEq)
eqHighInput.addEventListener('input', emitEq)
gainInput.addEventListener('input', emitGain)
tempoInput.addEventListener('input', emitTempo)

// Periodic RMS measurement
setInterval(() => {
  if (analyser) {
    const buf = new Float32Array(analyser.fftSize)
    analyser.getFloatTimeDomainData(buf)
    let s = 0
    for (const v of buf) s += v * v
    const rms = Math.sqrt(s / buf.length)
    remoteRmsDb = rms > 0 ? 20 * Math.log10(rms) : -120
  }
  report()
}, 500)
