const { app, BrowserWindow, Menu, ipcMain } = require('electron');
const path = require('node:path');
const os = require('node:os');
const dgram = require('node:dgram');
const fs = require('node:fs');
const { execFile } = require('node:child_process');
const { WebSocketServer, WebSocket } = require('ws');

const DISCOVERY_PORT = 3766;
const USB_DIRECT_PORT = 3767;
const ADB_REVERSE_PORTS = [3765, USB_DIRECT_PORT];

let mainWindow;
let signalingServer;
let discoveryServer;
let usbDirectServer;
let androidSocket;
let usbSocket;
let pairCode = createPairCode();
let serverInfo = { port: 0, addresses: [], pairCode };
let normalWindowBounds;
let deviceModeActive = false;
let adbMonitorTimer;
let lastAdbDeviceKey = '';
let reverseReadyDevices = new Set();
let usbConnection = {
  state: 'waiting',
  message: '等待数据线连接',
  devices: []
};

function createPairCode() {
  return String(Math.floor(100000 + Math.random() * 900000));
}

function createWindow() {
  Menu.setApplicationMenu(null);

  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 980,
    minHeight: 640,
    title: 'G享屏',
    backgroundColor: '#f4f6f8',
    icon: path.join(__dirname, '..', 'assets', 'g-share-icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  mainWindow.webContents.once('did-finish-load', () => {
    sendToRenderer('server-info', serverInfo);
    sendToRenderer('usb-connection', usbConnection);
  });
  mainWindow.on('closed', () => {
    mainWindow = undefined;
  });
}

function sendToRenderer(channel, payload) {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.webContents.send(channel, payload);
}

function getLanAddresses(port) {
  const interfaces = os.networkInterfaces();
  const addresses = [];
  for (const entries of Object.values(interfaces)) {
    for (const entry of entries || []) {
      if (entry.family === 'IPv4' && !entry.internal) {
        addresses.push(`ws://${entry.address}:${port}`);
      }
    }
  }
  addresses.push(`ws://127.0.0.1:${port}`);
  return addresses;
}

function pickAddressForRemote(remoteAddress, port) {
  const normalizedRemote = remoteAddress.replace(/^::ffff:/, '');
  const addresses = getLanAddresses(port)
    .map((url) => url.replace(/^ws:\/\//, '').replace(/:\d+$/, ''));

  const remotePrefix = normalizedRemote.split('.').slice(0, 3).join('.');
  const sameSubnet = addresses.find((address) => address.startsWith(`${remotePrefix}.`));
  return `ws://${sameSubnet || addresses[0] || '127.0.0.1'}:${port}`;
}

function updateServerInfo(port) {
  serverInfo = {
    port,
    addresses: getLanAddresses(port),
    pairCode,
    discoveryPort: DISCOVERY_PORT,
    usbDirectPort: USB_DIRECT_PORT,
    usbConnection
  };
  sendToRenderer('server-info', serverInfo);
}

function updateUsbConnection(next) {
  usbConnection = {
    ...usbConnection,
    ...next,
    devices: next.devices || usbConnection.devices || []
  };
  serverInfo = {
    ...serverInfo,
    usbConnection
  };
  sendToRenderer('usb-connection', usbConnection);
  sendToRenderer('server-info', serverInfo);
}

function startSignalingServer(port = 3765) {
  const wss = new WebSocketServer({ port });
  wss.once('listening', () => {
    signalingServer = wss;
    updateServerInfo(port);
    sendToRenderer('status', `信令服务已启动：${port}`);
    startDiscoveryServer();
  });

  wss.once('error', (error) => {
    if (error.code === 'EADDRINUSE' && port < 3865) {
      startSignalingServer(port + 1);
      return;
    }
    sendToRenderer('status', `信令服务启动失败：${error.message}`);
  });

  wss.on('connection', (socket, request) => {
    if (androidSocket && androidSocket.readyState === WebSocket.OPEN) {
      androidSocket.close(1000, '已由新的 Android 客户端替换');
    }
    androidSocket = socket;
    sendToRenderer('status', `Android 已连接：${request.socket.remoteAddress}`);

    socket.on('message', (raw) => {
      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch (error) {
        sendToRenderer('status', `信令消息解析失败：${error.message}`);
        return;
      }
      sendToRenderer('signal-from-android', message);
    });

    socket.on('close', () => {
      if (androidSocket === socket) {
        androidSocket = undefined;
      }
      sendToRenderer('status', 'Android 已断开');
      sendToRenderer('android-disconnected', {});
    });

    socket.on('error', (error) => {
      sendToRenderer('status', `Android WebSocket 错误：${error.message}`);
    });
  });
}

function startDiscoveryServer() {
  if (discoveryServer || !serverInfo.port) return;

  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  discoveryServer = socket;

  socket.on('error', (error) => {
    sendToRenderer('status', `发现服务错误：${error.message}`);
  });

  socket.on('message', (buffer, remote) => {
    let message;
    try {
      message = JSON.parse(buffer.toString('utf8'));
    } catch {
      return;
    }

    if (message.type !== 'screen-share-discover') return;
    if (String(message.code) !== pairCode) return;

    const wsUrl = pickAddressForRemote(remote.address, serverInfo.port);
    const response = Buffer.from(JSON.stringify({
      type: 'screen-share-peer',
      code: pairCode,
      wsUrl
    }));

    socket.send(response, remote.port, remote.address);
    sendToRenderer('status', `配对成功：${remote.address}，已返回 ${wsUrl}`);
  });

  socket.bind(DISCOVERY_PORT, () => {
    socket.setBroadcast(true);
    sendToRenderer('status', `发现服务已启动：UDP ${DISCOVERY_PORT}`);
  });
}

function startUsbDirectServer(port = USB_DIRECT_PORT) {
  const wss = new WebSocketServer({ port });
  usbDirectServer = wss;

  wss.once('listening', () => {
    sendToRenderer('status', `USB 直连服务已启动：${port}`);
    updateUsbConnection({
      state: reverseReadyDevices.size > 0 ? 'ready' : 'waiting',
      message: reverseReadyDevices.size > 0 ? 'USB 已就绪，可以开始共享' : '等待数据线连接'
    });
  });

  wss.once('error', (error) => {
    sendToRenderer('status', `USB 直连服务启动失败：${error.message}`);
    updateUsbConnection({
      state: 'error',
      message: `USB 服务启动失败：${error.message}`
    });
  });

  wss.on('connection', (socket, request) => {
    let streamSession = false;

    socket.on('message', (raw, isBinary) => {
      if (isBinary) {
        handleUsbBinary(socket, raw);
        return;
      }

      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch (error) {
        sendToRenderer('status', `USB 消息解析失败：${error.message}`);
        return;
      }

      if (message.type === 'usb-probe') {
        socket.send(JSON.stringify({
          type: 'usb-peer',
          code: pairCode,
          url: `usb://127.0.0.1:${USB_DIRECT_PORT}?code=${pairCode}`
        }));
        socket.close(1000, 'probe done');
        return;
      }

      if (message.type === 'usb-start') {
        if (usbSocket && usbSocket.readyState === WebSocket.OPEN && usbSocket !== socket) {
          usbSocket.close(1000, '已由新的 USB 客户端替换');
        }
        usbSocket = socket;
        streamSession = true;
        closeLanAndroidSocket();
        updateUsbConnection({
          state: 'active',
          message: 'USB 直连共享中'
        });
        sendToRenderer('usb-started', message);
        sendToRenderer('status', `USB Android 已连接：${request.socket.remoteAddress}`);
        return;
      }

      if (message.type === 'usb-status') {
        sendToRenderer('status', `USB：${message.message || ''}`);
        return;
      }

      if (message.type === 'usb-display') {
        sendToRenderer('usb-display-meta', message);
      }
    });

    socket.on('close', () => {
      if (usbSocket === socket) {
        usbSocket = undefined;
      }
      if (streamSession) {
        updateUsbConnection({
          state: reverseReadyDevices.size > 0 ? 'ready' : 'waiting',
          message: reverseReadyDevices.size > 0 ? 'USB 已就绪，可以开始共享' : '等待数据线连接'
        });
        sendToRenderer('status', 'USB Android 已断开');
        sendToRenderer('android-disconnected', {});
      }
    });

    socket.on('error', (error) => {
      sendToRenderer('status', `USB WebSocket 错误：${error.message}`);
    });
  });
}

function handleUsbBinary(socket, raw) {
  if (socket !== usbSocket) return;
  const buffer = Buffer.isBuffer(raw) ? raw : Buffer.from(raw);
  if (buffer.length < 2) return;

  const type = buffer[0];
  const payload = buffer.subarray(1);
  if (type === 1) {
    sendToRenderer('usb-video-frame', payload);
  } else if (type === 2) {
    sendToRenderer('usb-audio-frame', payload);
  }
}

function closeLanAndroidSocket() {
  if (androidSocket && androidSocket.readyState === WebSocket.OPEN) {
    androidSocket.close(1000, '已切换到 USB 直连');
  }
  androidSocket = undefined;
}

function startAdbReverseMonitor() {
  runAdbReverseSetup();
  adbMonitorTimer = setInterval(runAdbReverseSetup, 5000);
}

function runAdbReverseSetup() {
  const adb = findAdbExecutable();
  execFile(adb, ['devices'], { windowsHide: true }, (error, stdout) => {
    if (error) {
      if (lastAdbDeviceKey !== 'adb-error') {
        lastAdbDeviceKey = 'adb-error';
        reverseReadyDevices = new Set();
        updateUsbConnection({
          state: 'error',
          message: '未找到 ADB，无法启用 USB 直连',
          devices: []
        });
        sendToRenderer('status', 'USB：未找到 ADB，数据线直连需要 Android SDK platform-tools');
      }
      return;
    }

    const devices = stdout
      .split(/\r?\n/)
      .slice(1)
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [serial, state] = line.split(/\s+/);
        return { serial, state };
      });
    const readyDevices = devices.filter((device) => device.state === 'device');
    const unauthorizedDevices = devices.filter((device) => device.state && device.state !== 'device');
    const nextKey = readyDevices.map((device) => device.serial).join(',');

    if (!readyDevices.length) {
      if (lastAdbDeviceKey !== nextKey) {
        reverseReadyDevices = new Set();
        if (unauthorizedDevices.length) {
          updateUsbConnection({
            state: 'warning',
            message: '手机尚未授权 USB 调试',
            devices: unauthorizedDevices.map((device) => device.serial)
          });
          sendToRenderer('status', 'USB：手机尚未授权 USB 调试，请在手机上允许这台电脑');
        } else if (lastAdbDeviceKey) {
          updateUsbConnection({
            state: 'waiting',
            message: '等待数据线连接',
            devices: []
          });
          sendToRenderer('status', 'USB：未检测到已授权的 Android 设备');
        } else {
          updateUsbConnection({
            state: 'waiting',
            message: '等待数据线连接',
            devices: []
          });
        }
        lastAdbDeviceKey = nextKey;
      }
      return;
    }

    if (lastAdbDeviceKey !== nextKey) {
      reverseReadyDevices = new Set();
      lastAdbDeviceKey = nextKey;
    }

    readyDevices.forEach((device) => configureAdbReverse(adb, device.serial));
  });
}

function configureAdbReverse(adb, serial) {
  if (reverseReadyDevices.has(serial)) return;

  let remaining = ADB_REVERSE_PORTS.length;
  let failed = false;
  ADB_REVERSE_PORTS.forEach((port) => {
    execFile(
      adb,
      ['-s', serial, 'reverse', `tcp:${port}`, `tcp:${port}`],
      { windowsHide: true },
      (error) => {
        if (error) {
          failed = true;
        }
        remaining -= 1;
        if (remaining > 0) return;

        if (failed) {
          updateUsbConnection({
            state: 'error',
            message: `${serial} USB 端口映射失败`,
            devices: [serial]
          });
          sendToRenderer('status', `USB：${serial} 端口映射失败，请确认 USB 调试已授权`);
          return;
        }
        reverseReadyDevices.add(serial);
        updateUsbConnection({
          state: 'ready',
          message: `${serial} 已就绪，可以开始共享`,
          devices: Array.from(reverseReadyDevices)
        });
        sendToRenderer('status', `USB：${serial} 已就绪，可用数据线直连共享`);
      }
    );
  });
}

function findAdbExecutable() {
  const candidates = [
    process.env.ADB_PATH,
    process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME, 'platform-tools', 'adb.exe'),
    process.env.ANDROID_SDK_ROOT && path.join(process.env.ANDROID_SDK_ROOT, 'platform-tools', 'adb.exe'),
    'C:\\Program Files\\Android\\platform-tools\\adb.exe'
  ].filter(Boolean);

  return candidates.find((candidate) => fs.existsSync(candidate)) || 'adb';
}

ipcMain.handle('get-server-info', () => serverInfo);

ipcMain.handle('refresh-pair-code', () => {
  pairCode = createPairCode();
  updateServerInfo(serverInfo.port);
  sendToRenderer('status', `配对码已刷新：${pairCode}`);
  return serverInfo;
});

ipcMain.on('signal-to-android', (_event, message) => {
  if (!androidSocket || androidSocket.readyState !== WebSocket.OPEN) {
    sendToRenderer('status', 'Android 未连接，信令未发送');
    return;
  }
  androidSocket.send(JSON.stringify(message));
});

ipcMain.handle('enter-device-mode', (_event, size = {}) => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  if (!deviceModeActive) {
    normalWindowBounds = mainWindow.getBounds();
  }
  deviceModeActive = true;
  const width = clamp(Number(size.width) || 420, 320, 1100);
  const height = clamp(Number(size.height) || 760, 360, 1000);
  mainWindow.setMinimumSize(260, 220);
  mainWindow.setContentSize(Math.round(width), Math.round(height), true);
  mainWindow.center();
});

ipcMain.handle('exit-device-mode', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  deviceModeActive = false;
  if (normalWindowBounds) {
    mainWindow.setBounds(normalWindowBounds, true);
    normalWindowBounds = undefined;
  } else {
    mainWindow.setContentSize(1280, 820, true);
    mainWindow.center();
  }
  mainWindow.setMinimumSize(980, 640);
});

ipcMain.handle('enter-window-fullscreen', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.setFullScreen(false);
  mainWindow.maximize();
});

ipcMain.handle('exit-window-fullscreen', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.setFullScreen(false);
  if (mainWindow.isMaximized()) {
    mainWindow.unmaximize();
  }
});

ipcMain.handle('enter-native-fullscreen', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.setFullScreen(true);
});

ipcMain.handle('exit-native-fullscreen', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return;
  mainWindow.setFullScreen(false);
});

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

app.whenReady().then(() => {
  app.setName('G享屏');
  createWindow();
  startSignalingServer(3765);
  startUsbDirectServer();
  startAdbReverseMonitor();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
      sendToRenderer('server-info', serverInfo);
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  if (adbMonitorTimer) {
    clearInterval(adbMonitorTimer);
    adbMonitorTimer = undefined;
  }
  if (androidSocket && androidSocket.readyState === WebSocket.OPEN) {
    androidSocket.close(1000, 'PC 客户端关闭');
  }
  if (usbSocket && usbSocket.readyState === WebSocket.OPEN) {
    usbSocket.close(1000, 'PC 客户端关闭');
  }
  signalingServer?.close();
  discoveryServer?.close();
  usbDirectServer?.close();
});
