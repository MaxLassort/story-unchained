const { contextBridge } = require('electron');

contextBridge.exposeInMainWorld('studioDesktop', {
  apiUrl: 'http://localhost:9090',
  versions: {
    electron: process.versions.electron,
    chrome: process.versions.chrome,
    node: process.versions.node,
  },
});
