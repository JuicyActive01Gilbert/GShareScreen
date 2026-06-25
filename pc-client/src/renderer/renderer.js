const bridge = window.screenBridge;

const viewer = document.getElementById('viewer');
const videoFrame = document.getElementById('videoFrame');
const remoteVideo = document.getElementById('remoteVideo');
const usbVideo = document.getElementById('usbVideo');
const emptyState = document.getElementById('emptyState');
const videoMeta = document.getElementById('videoMeta');
const pairCode = document.getElementById('pairCode');
const usbStatus = document.getElementById('usbStatus');
const usbStatusText = document.getElementById('usbStatusText');
const refreshCodeButton = document.getElementById('refreshCodeButton');
const deviceModeButton = document.getElementById('deviceModeButton');
const windowFullscreenButton = document.getElementById('windowFullscreenButton');
const nativeFullscreenButton = document.getElementById('nativeFullscreenButton');
const restoreButton = document.getElementById('restoreButton');
const exitImmersiveButton = document.getElementById('exitImmersiveButton');
const exitNativeFullscreenButton = document.getElementById('exitNativeFullscreenButton');
const volumeSlider = document.getElementById('volumeSlider');
const audioState = document.getElementById('audioState');

let peerConnection;
let pendingCandidates = [];
let remoteStream;
let audioContext;
let audioProcessor;
let activeAudioChannel;
let audioChunks = [];
let audioChunkOffset = 0;
let queuedSamples = 0;
let metaTimer;
let resizeObserver;
let deviceMode = false;
let inputMode = 'idle';
let usbFrameUrl;
let usbSourceWidth = 0;
let usbSourceHeight = 0;
let displayMetaWidth = 0;
let displayMetaHeight = 0;
let audioVolume = 0.8;
let immersiveMode = false;
let nativeFullscreen = false;
let lastDeviceModeSize = { width: 0, height: 0 };
let lastRenderedSource = { mode: 'idle', width: 0, height: 0 };

function appendStatus(message) {
  console.log(`[G享屏] ${message}`);
}

function renderServerInfo(info) {
  pairCode.textContent = info.pairCode || '------';
  if (info.usbConnection) {
    renderUsbConnection(info.usbConnection);
  }
}

function renderUsbConnection(info = {}) {
  const state = info.state || 'waiting';
  usbStatus.className = `usb-status ${state}`;
  usbStatusText.textContent = info.message || '等待数据线连接';
}

function createPeerConnection() {
  clearUsbStream();
  displayMetaWidth = 0;
  displayMetaHeight = 0;
  remoteVideo.style.transform = 'none';
  inputMode = 'webrtc';
  closePeerConnection({ clearVideo: true });

  pendingCandidates = [];
  peerConnection = new RTCPeerConnection({ iceServers: [] });

  peerConnection.ontrack = (event) => {
    const [stream] = event.streams;
    remoteStream = stream || new MediaStream([event.track]);
    remoteVideo.srcObject = remoteStream;
    emptyState.classList.add('hidden');
    appendStatus(`收到远端 ${event.track.kind} 轨道`);
    startVideoMetaPolling();
    fitVideoToViewer();
  };

  peerConnection.ondatachannel = (event) => {
    const channel = event.channel;
    appendStatus(`收到数据通道：${channel.label}`);
    if (channel.label === 'system-audio-pcm') {
      setupAudioChannel(channel);
    }
  };

  peerConnection.onicecandidate = (event) => {
    if (!event.candidate) return;
    bridge.sendSignal({
      type: 'candidate',
      sdpMid: event.candidate.sdpMid,
      sdpMLineIndex: event.candidate.sdpMLineIndex,
      candidate: event.candidate.candidate
    });
  };

  peerConnection.onconnectionstatechange = () => {
    appendStatus(`WebRTC 状态：${peerConnection.connectionState}`);
    if (['failed', 'closed'].includes(peerConnection.connectionState)) {
      closePeerConnection({ clearVideo: true });
    }
  };

  peerConnection.oniceconnectionstatechange = () => {
    appendStatus(`ICE 状态：${peerConnection.iceConnectionState}`);
  };

  return peerConnection;
}

async function handleOffer(message) {
  const pc = createPeerConnection();
  await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
  appendStatus('已接收 Android Offer');

  for (const candidate of pendingCandidates) {
    await pc.addIceCandidate(candidate);
  }
  pendingCandidates = [];

  const answer = await pc.createAnswer();
  await pc.setLocalDescription(answer);
  bridge.sendSignal({ type: 'answer', sdp: answer.sdp });
  appendStatus('已发送 Answer');
}

async function handleCandidate(message) {
  const candidate = {
    candidate: message.candidate,
    sdpMid: message.sdpMid,
    sdpMLineIndex: message.sdpMLineIndex
  };

  if (!peerConnection || !peerConnection.remoteDescription) {
    pendingCandidates.push(candidate);
    return;
  }
  await peerConnection.addIceCandidate(candidate);
}

function closePeerConnection({ clearVideo }) {
  if (metaTimer) {
    clearInterval(metaTimer);
    metaTimer = undefined;
  }
  pendingCandidates = [];

  if (activeAudioChannel) {
    activeAudioChannel.onmessage = null;
    activeAudioChannel.onopen = null;
    activeAudioChannel.onclose = null;
    activeAudioChannel = undefined;
  }
  resetAudioBuffer();

  if (peerConnection) {
    peerConnection.ontrack = null;
    peerConnection.ondatachannel = null;
    peerConnection.onicecandidate = null;
    peerConnection.onconnectionstatechange = null;
    peerConnection.oniceconnectionstatechange = null;
    for (const sender of peerConnection.getSenders()) {
      sender.track?.stop();
    }
    for (const receiver of peerConnection.getReceivers()) {
      receiver.track?.stop();
    }
    peerConnection.close();
    peerConnection = undefined;
  }

  if (remoteStream) {
    for (const track of remoteStream.getTracks()) {
      track.stop();
    }
    remoteStream = undefined;
  }

  if (clearVideo) {
    remoteVideo.pause();
    remoteVideo.removeAttribute('src');
    remoteVideo.srcObject = null;
    remoteVideo.load();
    remoteVideo.style.width = '0px';
    remoteVideo.style.height = '0px';
    remoteVideo.style.transform = 'none';
    lastRenderedSource = { mode: 'idle', width: 0, height: 0 };
    if (inputMode !== 'usb') {
      emptyState.classList.remove('hidden');
    }
    videoMeta.textContent = '等待画面';
  }
}

function ensureAudioContext() {
  if (audioContext) return audioContext;

  audioContext = new AudioContext({ sampleRate: 48000 });
  audioProcessor = audioContext.createScriptProcessor(4096, 0, 2);
  audioProcessor.onaudioprocess = (event) => {
    const left = event.outputBuffer.getChannelData(0);
    const right = event.outputBuffer.getChannelData(1);

    for (let i = 0; i < left.length; i += 1) {
      const sample = readAudioSample();
      left[i] = sample * audioVolume;
      right[i] = readAudioSample() * audioVolume;
    }
  };
  audioProcessor.connect(audioContext.destination);
  return audioContext;
}

function setupAudioChannel(channel) {
  activeAudioChannel = channel;
  channel.binaryType = 'arraybuffer';
  channel.onopen = () => {
    ensureAudioContext().resume();
    appendStatus('声音数据通道已打开');
  };
  channel.onclose = () => {
    appendStatus('声音数据通道已关闭');
    activeAudioChannel = undefined;
    resetAudioBuffer();
  };
  channel.onmessage = async (event) => {
    const buffer = event.data instanceof Blob ? await event.data.arrayBuffer() : event.data;
    enqueuePcm(buffer);
  };
}

function beginUsbStream(message = {}) {
  closePeerConnection({ clearVideo: true });
  clearUsbStream();
  inputMode = 'usb';
  emptyState.classList.add('hidden');
  remoteVideo.style.display = 'none';
  usbVideo.style.display = 'block';
  videoMeta.textContent = message.quality ? `USB ${message.quality}` : 'USB 直连';
  appendStatus('USB 直连画面已接入');
}

function handleUsbVideoFrame(buffer) {
  if (inputMode !== 'usb') beginUsbStream();
  const blob = new Blob([normalizeArrayBuffer(buffer)], { type: 'image/jpeg' });
  const nextUrl = URL.createObjectURL(blob);
  const oldUrl = usbFrameUrl;
  usbFrameUrl = nextUrl;
  usbVideo.onload = () => {
    const previousWidth = usbSourceWidth;
    const previousHeight = usbSourceHeight;
    usbSourceWidth = usbVideo.naturalWidth;
    usbSourceHeight = usbVideo.naturalHeight;
    if (previousWidth !== usbSourceWidth || previousHeight !== usbSourceHeight) {
      resetMediaElementSize(usbVideo);
    }
    videoMeta.textContent = `USB ${usbSourceWidth} × ${usbSourceHeight}`;
    scheduleFit();
    if (deviceMode) {
      syncDeviceModeWindow();
    }
    if (oldUrl) URL.revokeObjectURL(oldUrl);
  };
  usbVideo.src = nextUrl;
}

function handleUsbDisplayMeta(meta = {}) {
  const width = Number(meta.width) || 0;
  const height = Number(meta.height) || 0;
  if (width <= 0 || height <= 0) return;
  const changed = width !== displayMetaWidth || height !== displayMetaHeight;
  displayMetaWidth = width;
  displayMetaHeight = height;
  if (changed) {
    appendStatus(`Android 显示尺寸：${width} × ${height}`);
  }
  if (inputMode === 'usb' && (!usbSourceWidth || !usbSourceHeight)) {
    videoMeta.textContent = `USB ${width} × ${height}`;
  }
}

function clearUsbStream() {
  if (usbFrameUrl) {
    URL.revokeObjectURL(usbFrameUrl);
    usbFrameUrl = undefined;
  }
  usbVideo.removeAttribute('src');
  usbVideo.style.display = 'none';
  usbVideo.style.width = '0px';
  usbVideo.style.height = '0px';
  usbVideo.style.transform = 'none';
  usbSourceWidth = 0;
  usbSourceHeight = 0;
  displayMetaWidth = 0;
  displayMetaHeight = 0;
  lastRenderedSource = { mode: 'idle', width: 0, height: 0 };
  if (inputMode === 'usb') {
    inputMode = 'idle';
    emptyState.classList.remove('hidden');
    videoMeta.textContent = '等待画面';
  }
  remoteVideo.style.display = 'block';
}

function enqueuePcm(arrayBuffer) {
  const normalizedBuffer = normalizeArrayBuffer(arrayBuffer);
  const view = new DataView(normalizedBuffer);
  const samples = new Float32Array(view.byteLength / 2);
  for (let index = 0; index < samples.length; index += 1) {
    samples[index] = view.getInt16(index * 2, true) / 32768;
  }
  audioChunks.push(samples);
  queuedSamples += samples.length;

  const maxQueuedSamples = 48000 * 2;
  while (queuedSamples > maxQueuedSamples && audioChunks.length > 1) {
    queuedSamples -= audioChunks.shift().length;
    audioChunkOffset = 0;
  }
}

function normalizeArrayBuffer(value) {
  if (value instanceof ArrayBuffer) return value;
  if (ArrayBuffer.isView(value)) {
    return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
  }
  return new Uint8Array(value).buffer;
}

function resetAudioBuffer() {
  audioChunks = [];
  audioChunkOffset = 0;
  queuedSamples = 0;
  audioState.textContent = `${Math.round(audioVolume * 100)}%`;
}

function readAudioSample() {
  while (audioChunks.length > 0) {
    const chunk = audioChunks[0];
    if (audioChunkOffset < chunk.length) {
      queuedSamples -= 1;
      return chunk[audioChunkOffset++];
    }
    audioChunks.shift();
    audioChunkOffset = 0;
  }
  return 0;
}

function startVideoMetaPolling() {
  if (metaTimer) clearInterval(metaTimer);
  const update = () => {
    if (remoteVideo.videoWidth > 0 && remoteVideo.videoHeight > 0) {
      videoMeta.textContent = `${remoteVideo.videoWidth} × ${remoteVideo.videoHeight}`;
      scheduleFit();
      if (deviceMode) {
        syncDeviceModeWindow();
      }
    }
  };
  remoteVideo.onloadedmetadata = update;
  remoteVideo.onresize = update;
  metaTimer = setInterval(update, 1000);
  update();
}

function fitVideoToViewer() {
  const { width: sourceWidth, height: sourceHeight, element } = getActiveSource();
  const bounds = viewer.getBoundingClientRect();
  if (!sourceWidth || !sourceHeight || bounds.width <= 0 || bounds.height <= 0) return;

  if (
    lastRenderedSource.mode !== inputMode ||
    lastRenderedSource.width !== sourceWidth ||
    lastRenderedSource.height !== sourceHeight
  ) {
    resetMediaElementSize(element);
    lastRenderedSource = { mode: inputMode, width: sourceWidth, height: sourceHeight };
  }

  const scale = Math.min(bounds.width / sourceWidth, bounds.height / sourceHeight);
  const fittedWidth = Math.max(1, Math.floor(sourceWidth * scale));
  const fittedHeight = Math.max(1, Math.floor(sourceHeight * scale));
  element.style.transform = 'none';
  element.style.width = `${fittedWidth}px`;
  element.style.height = `${fittedHeight}px`;
}

function scheduleFit() {
  requestAnimationFrame(fitVideoToViewer);
  setTimeout(fitVideoToViewer, 60);
  setTimeout(fitVideoToViewer, 180);
  setTimeout(fitVideoToViewer, 360);
}

function resetFrameLayout() {
  videoFrame.style.width = '';
  videoFrame.style.height = '';
  scheduleFit();
}

function resetMediaElementSize(element) {
  element.style.width = '0px';
  element.style.height = '0px';
  element.style.transform = 'none';
}

function getDeviceModeSize() {
  const source = getActiveSource();
  const sourceWidth = source.width || 420;
  const sourceHeight = source.height || 760;
  const portrait = sourceHeight >= sourceWidth;
  const maxWidth = Math.min(portrait ? 520 : 900, Math.floor(screen.availWidth * 0.82));
  const maxHeight = Math.min(portrait ? 900 : 560, Math.floor(screen.availHeight * 0.86));
  const scale = Math.min(maxWidth / sourceWidth, maxHeight / sourceHeight, 1);
  return {
    width: Math.max(320, Math.round(sourceWidth * scale)),
    height: Math.max(360, Math.round(sourceHeight * scale))
  };
}

function syncDeviceModeWindow(force = false) {
  if (!deviceMode) return;
  const size = getDeviceModeSize();
  if (!force && size.width === lastDeviceModeSize.width && size.height === lastDeviceModeSize.height) {
    return;
  }
  lastDeviceModeSize = size;
  bridge.enterDeviceMode(size)
    .then(scheduleFit)
    .catch((error) => appendStatus(`模拟器模式调整失败：${error.message}`));
}

function getActiveSource() {
  if (inputMode === 'usb') {
    return {
      width: usbSourceWidth,
      height: usbSourceHeight,
      element: usbVideo
    };
  }
  return {
    width: remoteVideo.videoWidth,
    height: remoteVideo.videoHeight,
    element: remoteVideo
  };
}

async function enterDeviceMode() {
  if (nativeFullscreen) {
    await bridge.exitNativeFullscreen();
    nativeFullscreen = false;
  }
  if (immersiveMode) {
    document.body.classList.remove('immersive-mode');
    immersiveMode = false;
  }
  deviceMode = true;
  document.body.classList.add('device-mode');
  lastDeviceModeSize = getDeviceModeSize();
  await bridge.enterDeviceMode(lastDeviceModeSize);
  scheduleFit();
}

async function exitDeviceMode() {
  deviceMode = false;
  lastDeviceModeSize = { width: 0, height: 0 };
  document.body.classList.remove('device-mode');
  await bridge.exitDeviceMode();
  resetFrameLayout();
}

async function enterWindowFullscreen() {
  immersiveMode = true;
  deviceMode = false;
  document.body.classList.remove('device-mode');
  document.body.classList.add('immersive-mode');
  await bridge.enterWindowFullscreen();
  scheduleFit();
}

async function exitWindowFullscreen() {
  immersiveMode = false;
  document.body.classList.remove('immersive-mode');
  await bridge.exitWindowFullscreen();
  resetFrameLayout();
}

async function enterNativeFullscreen() {
  nativeFullscreen = true;
  immersiveMode = true;
  deviceMode = false;
  document.body.classList.remove('device-mode');
  document.body.classList.add('immersive-mode');
  await bridge.enterNativeFullscreen();
  scheduleFit();
}

async function exitNativeFullscreen() {
  nativeFullscreen = false;
  immersiveMode = false;
  document.body.classList.remove('immersive-mode');
  await bridge.exitNativeFullscreen();
  resetFrameLayout();
}

async function restoreImmersiveMode() {
  if (nativeFullscreen) {
    await exitNativeFullscreen();
    return;
  }
  await exitWindowFullscreen();
}

function setVolume(value) {
  audioVolume = Math.max(0, Math.min(1, Number(value) / 100));
  audioState.textContent = `${Math.round(audioVolume * 100)}%`;
  ensureAudioContext().resume();
}

volumeSlider.addEventListener('input', () => {
  setVolume(volumeSlider.value);
});

refreshCodeButton.addEventListener('click', async () => {
  const info = await bridge.refreshPairCode();
  renderServerInfo(info);
});

deviceModeButton.addEventListener('click', enterDeviceMode);
windowFullscreenButton.addEventListener('click', enterWindowFullscreen);
nativeFullscreenButton.addEventListener('click', enterNativeFullscreen);
restoreButton.addEventListener('click', exitDeviceMode);
exitImmersiveButton.addEventListener('click', restoreImmersiveMode);
exitNativeFullscreenButton.addEventListener('click', exitNativeFullscreen);

bridge.onServerInfo(renderServerInfo);
bridge.onUsbConnection(renderUsbConnection);
bridge.onStatus(appendStatus);
bridge.onAndroidDisconnected(() => {
  closePeerConnection({ clearVideo: true });
  clearUsbStream();
});

bridge.onUsbStarted(beginUsbStream);
bridge.onUsbVideoFrame(handleUsbVideoFrame);
bridge.onUsbDisplayMeta(handleUsbDisplayMeta);
bridge.onUsbAudioFrame((buffer) => {
  ensureAudioContext().resume();
  enqueuePcm(buffer);
});

bridge.onSignal(async (message) => {
  try {
    if (message.type === 'offer') {
      await handleOffer(message);
    } else if (message.type === 'candidate') {
      await handleCandidate(message);
    } else if (message.type === 'display-meta') {
      handleUsbDisplayMeta(message);
    }
  } catch (error) {
    appendStatus(`信令处理失败：${error.message}`);
    closePeerConnection({ clearVideo: true });
  }
});

window.addEventListener('beforeunload', () => {
  closePeerConnection({ clearVideo: true });
  if (audioProcessor) {
    audioProcessor.disconnect();
    audioProcessor = undefined;
  }
  audioContext?.close();
  resizeObserver?.disconnect();
});

resizeObserver = new ResizeObserver(fitVideoToViewer);
resizeObserver.observe(viewer);
window.addEventListener('resize', scheduleFit);
bridge.getServerInfo().then(renderServerInfo);
setVolume(volumeSlider.value);
appendStatus('PC 端已启动');
