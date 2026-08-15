const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const { spawn, execSync } = require('child_process');
const path = require('path');
const http = require('http');
const fs = require('fs');

const API_PORT = 9090;
const READY_TIMEOUT_MS = 30_000;
const DEV_MODE = process.env.STUDIO_DEV === '1';
const FRONT_DEV_URL = 'http://localhost:4200';

let backendProcess = null;
let mainWindow = null;

/** Resolves the java binary: bundled JRE, then JAVA_HOME, then PATH. */
function resolveJava() {
  const bundled = path.join(process.resourcesPath || __dirname, 'jre');
  const bundledBin = path.join(bundled, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  if (fs.existsSync(bundledBin)) return bundledBin;
  if (process.env.JAVA_HOME) {
    const fromHome = path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (fs.existsSync(fromHome)) return fromHome;
  }
  return 'java';
}

/** Resolves the backend jar: packaged resources, then the Gradle build output (dev). */
function resolveJar() {
  const packaged = path.join(process.resourcesPath || __dirname, 'api.jar');
  if (fs.existsSync(packaged)) return packaged;
  const dev = path.join(__dirname, '..', '..', 'api', 'build', 'libs', 'api-0.0.1-SNAPSHOT.jar');
  return fs.existsSync(dev) ? dev : null;
}

function apiHealthy() {
  return new Promise((resolve) => {
    const req = http.get({ host: '127.0.0.1', port: API_PORT, path: '/', timeout: 2000 }, (res) => {
      let body = '';
      res.on('data', (chunk) => (body += chunk));
      res.on('end', () => resolve(body.includes('StoryUnchained Server running')));
    });
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

async function waitForApi(timeoutMs) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await apiHealthy()) return true;
    await new Promise((r) => setTimeout(r, 500));
  }
  return false;
}

async function startBackend() {
  if (await apiHealthy()) {
    console.log(`[desktop] API sain déjà sur :${API_PORT} — réutilisation.`);
    return;
  }
  const jar = resolveJar();
  if (!jar) {
    console.warn('[desktop] jar introuvable — backend non lancé');
    return;
  }
  const java = resolveJava();
  console.log(`[desktop] démarrage backend: ${java} -jar ${jar}`);
  backendProcess = spawn(java, ['-jar', jar], {
    cwd: path.dirname(jar),
    stdio: 'inherit',
    env: process.env,
  });
  backendProcess.on('exit', (code) => {
    console.log(`[desktop] backend arrêté (code ${code})`);
    backendProcess = null;
  });
  const ready = await waitForApi(READY_TIMEOUT_MS);
  if (!ready) console.warn('[desktop] backend non prêt après timeout');
}

function stopBackend() {
  if (!backendProcess || backendProcess.pid == null) return;
  const pid = backendProcess.pid;
  if (process.platform === 'win32') {
    try {
      execSync(`taskkill /pid ${pid} /T /F`, { stdio: 'ignore' });
    } catch {
      /* déjà arrêté */
    }
  } else {
    backendProcess.kill('SIGTERM');
  }
  backendProcess = null;
}

ipcMain.handle('dialog:openPath', async (event, options = {}) => {
  const win = BrowserWindow.fromWebContents(event.sender) || mainWindow;
  const result = await dialog.showOpenDialog(win, {
    title: options.title,
    defaultPath: options.defaultPath,
    buttonLabel: options.buttonLabel,
    properties: options.properties || ['openFile'],
    filters: options.filters,
  });
  if (result.canceled || result.filePaths.length === 0) return null;
  return result.filePaths[0];
});

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    title: 'StoryUnchained',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.on('closed', () => {
    mainWindow = null;
  });
  return mainWindow;
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    await startBackend();
    createWindow();
    if (DEV_MODE) {
      mainWindow.loadURL(FRONT_DEV_URL);
    } else {
      mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'browser', 'index.html'));
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') app.quit();
  });

  app.on('will-quit', stopBackend);
  app.on('quit', stopBackend);
}
