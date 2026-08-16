window.BANKING_CONFIG = window.BANKING_CONFIG || {};

const isLocalFile = window.location.protocol === 'file:';
const isLocalHost = ['localhost', '127.0.0.1', ''].includes(window.location.hostname);

window.BANKING_CONFIG.apiBaseUrl =
    window.BANKING_CONFIG.apiBaseUrl ||
    (isLocalFile || isLocalHost ? 'http://localhost:8080' : '');
