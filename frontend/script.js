'use strict';

const themeToggle = document.getElementById('themeToggle');
const confirmDialog = document.getElementById('confirmDialog');
const dialogBackdrop = document.getElementById('dialogBackdrop');
const confirmActionBtn = document.getElementById('confirmAction');
const cancelActionBtn = document.getElementById('cancelAction');

function getApiBaseUrl() {
    return (window.BANKING_CONFIG || {}).apiBaseUrl || '';
}
function apiUrl(path) {
    return `${getApiBaseUrl()}${path}`;
}
function getToken() {
    return sessionStorage.getItem('bank-token');
}
function authHeaders(extra = {}) {
    const token = getToken();
    return {
        ...extra,
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
    };
}
function handleUnauthorized() {
    sessionStorage.removeItem('bank-token');
    sessionStorage.removeItem('bank-username');
    window.location.replace('./login.html');
}

(function guardPage() {
    if (!getToken()) {
        window.location.replace('./login.html');
    }
})();

function applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('vault-theme', theme);
    const icon = themeToggle.querySelector('svg');
    if (theme === 'light') {
        icon.innerHTML = `<path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"
            fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round"/>`;
        themeToggle.setAttribute('aria-label', 'Switch to dark mode');
    } else {
        icon.innerHTML = `
            <circle cx="12" cy="12" r="5"/>
            <line x1="12" y1="1" x2="12" y2="3"/>
            <line x1="12" y1="21" x2="12" y2="23"/>
            <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"/>
            <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"/>
            <line x1="1" y1="12" x2="3" y2="12"/>
            <line x1="21" y1="12" x2="23" y2="12"/>
            <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"/>
            <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"/>`;
        themeToggle.setAttribute('aria-label', 'Switch to light mode');
    }
}

function initTheme() {
    applyTheme(localStorage.getItem('vault-theme') || 'dark');
}

themeToggle.addEventListener('click', () => {
    const current = document.documentElement.getAttribute('data-theme') || 'dark';
    applyTheme(current === 'dark' ? 'light' : 'dark');
});

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function formatMoney(value) {
    const amount = Number(value);
    if (Number.isNaN(amount)) return '₹ ' + escapeHtml(String(value));
    return new Intl.NumberFormat('en-IN', {
        style: 'currency',
        currency: 'INR',
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    }).format(amount);
}

function formatDate(iso) {
    if (!iso) return '';
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return escapeHtml(iso);
    return date.toLocaleString();
}

function showToast(el, text, type = 'success', duration = 5000) {
    el.textContent = text;
    el.className = `toast visible ${type}`;
    if (el._timer) clearTimeout(el._timer);
    el._timer = setTimeout(() => { el.className = 'toast'; }, duration);
}

function newIdempotencyKey() {
    if (crypto.randomUUID) return crypto.randomUUID();
    return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isValidAmount(raw) {
    return /^\d+(\.\d{1,2})?$/.test(String(raw).trim());
}

function accountCardHtml(account) {
    return `
        <div class="summary-row"><span>Customer</span><strong>${escapeHtml(account.customerName)}</strong></div>
        <div class="summary-row"><span>Account number</span><strong>${escapeHtml(account.accountNumber)}</strong></div>
        <div class="summary-row"><span>Phone</span><strong>${escapeHtml(account.phone)}</strong></div>
        <div class="summary-row"><span>Current balance</span><strong class="money">${formatMoney(account.balance)}</strong></div>
    `;
}

function renderAccount(container, account) {
    container.hidden = false;
    container.innerHTML = accountCardHtml(account);
}

async function api(path, options = {}) {
    const res = await fetch(apiUrl(path), options);
    if (res.status === 401) {
        handleUnauthorized();
        throw new Error('Unauthorized');
    }
    let data = {};
    try {
        data = await res.json();
    } catch {
        data = {};
    }
    if (!res.ok) {
        const err = new Error(data.error || `Request failed (${res.status})`);
        err.status = res.status;
        err.data = data;
        throw err;
    }
    return data;
}

document.querySelectorAll('.dash-nav-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
        const panel = btn.dataset.panel;
        document.querySelectorAll('.dash-nav-btn').forEach((b) => b.classList.toggle('is-active', b === btn));
        document.querySelectorAll('.panel').forEach((section) => {
            section.hidden = section.id !== `panel-${panel}`;
        });
        if (panel === 'history') {
            loadHistory();
        }
    });
});

const createForm = document.getElementById('createForm');
const createBtn = document.getElementById('createBtn');
const createMessage = document.getElementById('createMessage');
const createdAccount = document.getElementById('createdAccount');
let createKey = newIdempotencyKey();

createForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const accountNumber = document.getElementById('accountNumber').value.trim();
    const customerName = document.getElementById('customerName').value.trim();
    const phone = document.getElementById('phone').value.trim();
    const address = document.getElementById('address').value.trim();
    const initialBalance = document.getElementById('initialBalance').value.trim();
    const fields = { accountNumber, customerName, phone, address, initialBalance };
    let valid = true;
    Object.entries(fields).forEach(([key, value]) => {
        const error = document.getElementById(`${key}-error`);
        const input = document.getElementById(key);
        error.textContent = '';
        input.classList.remove('is-invalid');
        if (!value) {
            error.textContent = 'This field is required.';
            input.classList.add('is-invalid');
            valid = false;
        }
    });
    if (accountNumber && !/^[A-Za-z0-9-]{6,32}$/.test(accountNumber)) {
        document.getElementById('accountNumber-error').textContent = 'Use 6–32 letters, digits, or hyphens.';
        document.getElementById('accountNumber').classList.add('is-invalid');
        valid = false;
    }
    const phoneDigits = phone.replace(/[\s()-]/g, '');
    if (phone && !/^\+?[0-9]{10,15}$/.test(phoneDigits)) {
        document.getElementById('phone-error').textContent = 'Enter a 10–15 digit phone number.';
        document.getElementById('phone').classList.add('is-invalid');
        valid = false;
    }
    if (initialBalance && !isValidAmount(initialBalance)) {
        document.getElementById('initialBalance-error').textContent = 'Enter a valid amount (e.g. 100.00).';
        document.getElementById('initialBalance').classList.add('is-invalid');
        valid = false;
    }
    if (!valid) return;

    createBtn.disabled = true;
    createBtn.classList.add('is-loading');
    try {
        const account = await api('/api/accounts', {
            method: 'POST',
            headers: authHeaders({
                'Content-Type': 'application/json',
                'Idempotency-Key': createKey,
            }),
            body: JSON.stringify({
                accountNumber,
                customerName,
                phone: phoneDigits,
                address,
                initialBalance,
            }),
        });
        showToast(createMessage, 'Account created successfully.', 'success');
        renderAccount(createdAccount, account);
        createForm.reset();
        createKey = newIdempotencyKey();
    } catch (err) {
        showToast(createMessage, err.message, 'error');
    } finally {
        createBtn.disabled = false;
        createBtn.classList.remove('is-loading');
    }
});

function bindLookup(formId, inputId, errorId, btnId, summaryId, txnFormId, store) {
    document.getElementById(formId).addEventListener('submit', async (e) => {
        e.preventDefault();
        const accountNumber = document.getElementById(inputId).value.trim();
        const error = document.getElementById(errorId);
        const btn = document.getElementById(btnId);
        error.textContent = '';
        if (!accountNumber) {
            error.textContent = 'Account number is required.';
            return;
        }
        btn.disabled = true;
        btn.classList.add('is-loading');
        try {
            const account = await api(`/api/accounts?accountNumber=${encodeURIComponent(accountNumber)}`);
            store.account = account;
            store.key = newIdempotencyKey();
            renderAccount(document.getElementById(summaryId), account);
            document.getElementById(txnFormId).hidden = false;
        } catch (err) {
            store.account = null;
            document.getElementById(summaryId).hidden = true;
            document.getElementById(txnFormId).hidden = true;
            error.textContent = err.message;
        } finally {
            btn.disabled = false;
            btn.classList.remove('is-loading');
        }
    });
}

const creditState = { account: null, key: newIdempotencyKey() };
const withdrawState = { account: null, key: newIdempotencyKey(), amount: '' };

bindLookup('creditLookupForm', 'creditAccountNumber', 'creditAccountNumber-error',
    'creditLookupBtn', 'creditAccount', 'creditForm', creditState);
bindLookup('withdrawLookupForm', 'withdrawAccountNumber', 'withdrawAccountNumber-error',
    'withdrawLookupBtn', 'withdrawAccount', 'withdrawForm', withdrawState);

document.getElementById('creditForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const amount = document.getElementById('creditAmount').value.trim();
    const error = document.getElementById('creditAmount-error');
    const btn = document.getElementById('creditBtn');
    const message = document.getElementById('creditMessage');
    error.textContent = '';
    if (!creditState.account) {
        showToast(message, 'Find an account first.', 'error');
        return;
    }
    if (!isValidAmount(amount) || Number(amount) <= 0) {
        error.textContent = 'Enter an amount greater than zero.';
        return;
    }
    btn.disabled = true;
    btn.classList.add('is-loading');
    try {
        const result = await api(`/api/accounts/${encodeURIComponent(creditState.account.accountNumber)}/credit`, {
            method: 'POST',
            headers: authHeaders({
                'Content-Type': 'application/json',
                'Idempotency-Key': creditState.key,
            }),
            body: JSON.stringify({ amount }),
        });
        creditState.account = result.account;
        creditState.key = newIdempotencyKey();
        renderAccount(document.getElementById('creditAccount'), result.account);
        document.getElementById('creditAmount').value = '';
        showToast(message, `${result.message}. Updated balance: ${formatMoney(result.account.balance)}`, 'success');
    } catch (err) {
        showToast(message, err.message, 'error');
    } finally {
        btn.disabled = false;
        btn.classList.remove('is-loading');
    }
});

let pendingWithdraw = null;

document.getElementById('withdrawForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const amount = document.getElementById('withdrawAmount').value.trim();
    const error = document.getElementById('withdrawAmount-error');
    const message = document.getElementById('withdrawMessage');
    error.textContent = '';
    if (!withdrawState.account) {
        showToast(message, 'Find an account first.', 'error');
        return;
    }
    if (!isValidAmount(amount) || Number(amount) <= 0) {
        error.textContent = 'Enter an amount greater than zero.';
        return;
    }
    if (Number(amount) > Number(withdrawState.account.balance)) {
        error.textContent = 'Amount exceeds available balance.';
        return;
    }
    pendingWithdraw = amount;
    document.getElementById('dialog-desc').textContent =
        `Withdraw ${formatMoney(amount)} from ${withdrawState.account.customerName} (${withdrawState.account.accountNumber})? Available: ${formatMoney(withdrawState.account.balance)}.`;
    openDialog();
});

function openDialog() {
    confirmDialog.setAttribute('open', '');
    dialogBackdrop.classList.add('active');
    confirmActionBtn.focus();
    document.addEventListener('keydown', handleDialogKey);
}
function closeDialog() {
    confirmDialog.removeAttribute('open');
    dialogBackdrop.classList.remove('active');
    pendingWithdraw = null;
    document.removeEventListener('keydown', handleDialogKey);
}
function handleDialogKey(e) {
    if (e.key === 'Escape') closeDialog();
}
cancelActionBtn.addEventListener('click', closeDialog);
dialogBackdrop.addEventListener('click', closeDialog);

confirmActionBtn.addEventListener('click', async () => {
    if (!pendingWithdraw || !withdrawState.account) return;
    const amount = pendingWithdraw;
    const accountNumber = withdrawState.account.accountNumber;
    closeDialog();
    const btn = document.getElementById('withdrawBtn');
    const message = document.getElementById('withdrawMessage');
    btn.disabled = true;
    btn.classList.add('is-loading');
    try {
        const result = await api(`/api/accounts/${encodeURIComponent(accountNumber)}/withdraw`, {
            method: 'POST',
            headers: authHeaders({
                'Content-Type': 'application/json',
                'Idempotency-Key': withdrawState.key,
            }),
            body: JSON.stringify({ amount }),
        });
        withdrawState.account = result.account;
        withdrawState.key = newIdempotencyKey();
        renderAccount(document.getElementById('withdrawAccount'), result.account);
        document.getElementById('withdrawAmount').value = '';
        showToast(message, `${result.message}. Updated balance: ${formatMoney(result.account.balance)}`, 'success');
    } catch (err) {
        showToast(message, err.message, 'error');
    } finally {
        btn.disabled = false;
        btn.classList.remove('is-loading');
    }
});

async function loadHistory() {
    const accountNumber = document.getElementById('historyAccountNumber').value.trim();
    const list = document.getElementById('historyList');
    const message = document.getElementById('historyMessage');
    const btn = document.getElementById('historyBtn');
    btn.disabled = true;
    btn.classList.add('is-loading');
    list.innerHTML = '<div class="skeleton skeleton-item"></div><div class="skeleton skeleton-item"></div>';
    try {
        const query = accountNumber ? `?accountNumber=${encodeURIComponent(accountNumber)}` : '';
        const items = await api(`/api/transactions${query}`, { headers: authHeaders() });
        if (!items.length) {
            list.innerHTML = '<p class="empty-desc">No transactions yet.</p>';
            return;
        }
        list.innerHTML = items.map((txn) => `
            <article class="history-item">
                <div>
                    <strong class="history-type history-type--${escapeHtml(txn.type.toLowerCase())}">${escapeHtml(txn.type)}</strong>
                    <div class="history-meta">${escapeHtml(txn.accountNumber)} · ${escapeHtml(txn.performedBy)}</div>
                    <div class="history-meta">${formatDate(txn.createdAt)}</div>
                </div>
                <div class="history-amounts">
                    <div class="money">${formatMoney(txn.amount)}</div>
                    <div class="history-meta">${formatMoney(txn.previousBalance)} → ${formatMoney(txn.newBalance)}</div>
                </div>
            </article>
        `).join('');
        message.className = 'toast';
    } catch (err) {
        list.innerHTML = '';
        showToast(message, err.message, 'error');
    } finally {
        btn.disabled = false;
        btn.classList.remove('is-loading');
    }
}

document.getElementById('historyForm').addEventListener('submit', (e) => {
    e.preventDefault();
    loadHistory();
});

(function initUserBar() {
    const username = sessionStorage.getItem('bank-username') || '';
    const actionsEl = document.querySelector('.topbar-actions');
    if (!actionsEl) return;

    const pill = document.createElement('span');
    pill.className = 'user-pill';
    pill.setAttribute('aria-label', 'Logged in as ' + username);
    pill.innerHTML = `<span>${escapeHtml(username)}</span>`;

    const logoutBtn = document.createElement('button');
    logoutBtn.className = 'btn-logout';
    logoutBtn.setAttribute('aria-label', 'Sign out');
    logoutBtn.textContent = 'Sign out';
    logoutBtn.addEventListener('click', async () => {
        logoutBtn.disabled = true;
        try {
            await fetch(apiUrl('/api/logout'), { method: 'POST', headers: authHeaders() });
        } catch (_) { /* ignore */ }
        sessionStorage.removeItem('bank-token');
        sessionStorage.removeItem('bank-username');
        window.location.replace('./login.html');
    });

    actionsEl.prepend(pill);
    actionsEl.insertBefore(logoutBtn, actionsEl.querySelector('#themeToggle'));
})();

initTheme();
