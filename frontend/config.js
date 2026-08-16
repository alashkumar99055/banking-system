window.BANKING_CONFIG = window.BANKING_CONFIG || {};

const RENDER_API_BASE_URL = 'https://banking-system-backend-cwtm.onrender.com';
const isLocalHost = ['localhost', '127.0.0.1'].includes(window.location.hostname);

window.BANKING_CONFIG.apiBaseUrl =
    window.BANKING_CONFIG.apiBaseUrl ||
    (isLocalHost ? 'http://localhost:8080' : RENDER_API_BASE_URL);
