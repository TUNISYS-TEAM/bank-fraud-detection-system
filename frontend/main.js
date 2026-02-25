const { app, BrowserWindow } = require('electron');
const path = require('path');

let mainWindow;

function createWindow() {
    mainWindow = new BrowserWindow({
        width: 520,
        height: 650,
        minWidth: 520,
        minHeight: 650,
        maxWidth: 520,
        maxHeight: 650,
        center: true,
        title: 'Détection de Fraude Bancaire',
        icon: path.join(__dirname, 'src', 'favicon.ico'),
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true
        },
        // Fenêtre sans barre système supérieure "frameless"
        frame: false,
        resizable: true,
        autoHideMenuBar: true,
        backgroundColor: '#f5f7fa'
    });

    // En dev : charge le serveur Angular (ng serve)
    mainWindow.loadURL('http://localhost:4200');

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