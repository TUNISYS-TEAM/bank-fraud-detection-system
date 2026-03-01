const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');

let mainWindow;

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 520,
        height: 650,
        minWidth: 520,
        minHeight: 650,
        center: true,
        title: 'Détection de Fraude Bancaire',
        icon: path.join(__dirname, 'src', 'favicon.ico'),
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false
        },
        frame: false,
        resizable: true,
        autoHideMenuBar: true,
        backgroundColor: '#f5f7fa'
    });

    mainWindow.loadURL('http://localhost:4200');

    mainWindow.webContents.on('before-input-event', (event, input) => {
        const isCtrlOrCmd = input.control || input.meta;
        if (!isCtrlOrCmd) return;

        if (input.key === '=' || input.key === '+') {
            event.preventDefault();
            const z = mainWindow.webContents.getZoomLevel(); 
            mainWindow.webContents.setZoomLevel(z + 0.5);
            return;
        }

        if (input.key === '-') {
            event.preventDefault();
            const z = mainWindow.webContents.getZoomLevel();
            mainWindow.webContents.setZoomLevel(z - 0.5);
            return;
        }

        if (input.key === '0') {
            event.preventDefault();
            mainWindow.webContents.setZoomLevel(0);
            return;
        }
    });

    ipcMain.on('maximize-window', () => {
        if (mainWindow) {
            mainWindow.maximize();
        }
    });

    ipcMain.on('unmaximize-window', () => {
        if (mainWindow) {
            mainWindow.unmaximize();
            mainWindow.setSize(520, 600);
            mainWindow.center();
        }
    });

    ipcMain.on('close-window', () => {
        if (mainWindow) {
            mainWindow.close();
        }
    });

    mainWindow.on('closed', () => {
        mainWindow = null;
    });
}

app.whenReady().then(createWindow);

app.on('window-all-closed', () => {
    if (process.platform !== 'darwin') {
        app.quit();
    }
});

app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
        createWindow();
    }
});