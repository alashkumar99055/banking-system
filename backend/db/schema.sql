CREATE TABLE IF NOT EXISTS authentication (
    id UUID PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    account_number TEXT NOT NULL UNIQUE,
    customer_name TEXT NOT NULL,
    phone TEXT NOT NULL,
    address TEXT NOT NULL,
    balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transactions (
    id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(id),
    type TEXT NOT NULL CHECK (type IN ('CREDIT', 'WITHDRAW')),
    amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    previous_balance NUMERIC(19, 2) NOT NULL,
    new_balance NUMERIC(19, 2) NOT NULL,
    performed_by UUID NOT NULL REFERENCES authentication(id),
    idempotency_key TEXT UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transactions_account_id ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON transactions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_accounts_account_number ON accounts(account_number);
