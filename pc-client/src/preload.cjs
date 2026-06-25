const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('screenBridge', {
  getServerInfo: () => ipcRenderer.invoke('get-server-info'),
  refreshPairCode: () => ipcRenderer.invoke('refresh-pair-code'),
  enterDeviceMode: (size) => ipcRenderer.invoke('enter-device-mode', size),
  exitDeviceMode: () => ipcRenderer.invoke('exit-device-mode'),
  enterWindowFullscreen: () => ipcRenderer.invoke('enter-window-fullscreen'),
  exitWindowFullscreen: () => ipcRenderer.invoke('exit-window-fullscreen'),
  enterNativeFullscreen: () => ipcRenderer.invoke('enter-native-fullscreen'),
  exitNativeFullscreen: () => ipcRenderer.invoke('exit-native-fullscreen'),
  sendSignal: (message) => ipcRenderer.send('signal-to-android', message),
  onServerInfo: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('server-info', handler);
    return () => ipcRenderer.removeListener('server-info', handler);
  },
  onSignal: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('signal-from-android', handler);
    return () => ipcRenderer.removeListener('signal-from-android', handler);
  },
  onStatus: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('status', handler);
    return () => ipcRenderer.removeListener('status', handler);
  },
  onAndroidDisconnected: (callback) => {
    const handler = () => callback();
    ipcRenderer.on('android-disconnected', handler);
    return () => ipcRenderer.removeListener('android-disconnected', handler);
  },
  onUsbConnection: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('usb-connection', handler);
    return () => ipcRenderer.removeListener('usb-connection', handler);
  },
  onUsbStarted: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('usb-started', handler);
    return () => ipcRenderer.removeListener('usb-started', handler);
  },
  onUsbVideoFrame: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('usb-video-frame', handler);
    return () => ipcRenderer.removeListener('usb-video-frame', handler);
  },
  onUsbDisplayMeta: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('usb-display-meta', handler);
    return () => ipcRenderer.removeListener('usb-display-meta', handler);
  },
  onUsbAudioFrame: (callback) => {
    const handler = (_event, value) => callback(value);
    ipcRenderer.on('usb-audio-frame', handler);
    return () => ipcRenderer.removeListener('usb-audio-frame', handler);
  }
});
