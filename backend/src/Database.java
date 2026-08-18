import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class Database {
    public static final String TYPE_CREDIT = "CREDIT";
    public static final String TYPE_WITHDRAW = "WITHDRAW";

    private final String url;
    private final String username;
    private final String password;

    public Database(String url, String username, String password) throws SQLException {
        this.url = url;
        this.username = username;
        this.password = password;
        initSchema();
        seedStaffIfConfigured();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private void initSchema() throws SQLException {
        String authSql = "CREATE TABLE IF NOT EXISTS authentication (" +
                "id UUID PRIMARY KEY, " +
                "username TEXT NOT NULL UNIQUE, " +
                "password_hash TEXT NOT NULL, " +
                "created_at TIMESTAMP NOT NULL DEFAULT NOW()" +
                ")";
        String accountsSql = "CREATE TABLE IF NOT EXISTS accounts (" +
                "id UUID PRIMARY KEY, " +
                "account_number TEXT NOT NULL UNIQUE, " +
                "customer_name TEXT NOT NULL, " +
                "phone TEXT NOT NULL, " +
                "address TEXT NOT NULL, " +
                "balance NUMERIC(19, 2) NOT NULL CHECK (balance >= 0), " +
                "created_at TIMESTAMP NOT NULL DEFAULT NOW(), " +
                "updated_at TIMESTAMP NOT NULL DEFAULT NOW()" +
                ")";
        String transactionsSql = "CREATE TABLE IF NOT EXISTS transactions (" +
                "id UUID PRIMARY KEY, " +
                "account_id UUID NOT NULL REFERENCES accounts(id), " +
                "type TEXT NOT NULL CHECK (type IN ('CREDIT', 'WITHDRAW')), " +
                "amount NUMERIC(19, 2) NOT NULL CHECK (amount > 0), " +
                "previous_balance NUMERIC(19, 2) NOT NULL, " +
                "new_balance NUMERIC(19, 2) NOT NULL, " +
                "performed_by UUID NOT NULL REFERENCES authentication(id), " +
                "idempotency_key TEXT UNIQUE, " +
                "created_at TIMESTAMP NOT NULL DEFAULT NOW()" +
                ")";
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(authSql);
            statement.execute(accountsSql);
            statement.execute(transactionsSql);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_account_id ON transactions(account_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transactions_created_at ON transactions(created_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_accounts_account_number ON accounts(account_number)");
        }
    }

    private void seedStaffIfConfigured() throws SQLException {
        String staffUser = System.getenv("BANK_STAFF_USERNAME");
        String staffPass = System.getenv("BANK_STAFF_PASSWORD");
        if (staffUser == null || staffUser.isBlank() || staffPass == null || staffPass.isBlank()) {
            return;
        }
        if (!userExists(staffUser.trim())) {
            createUser(staffUser.trim(), staffPass);
            System.out.println("Seeded bank staff user: " + staffUser.trim());
        }
    }

    public static String hashPassword(String plainPassword) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            return computeHash(salt, plainPassword);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String computeHash(byte[] salt, String plainPassword) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt);
        digest.update(plainPassword.getBytes(StandardCharsets.UTF_8));
        byte[] hash = digest.digest();
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    public static boolean verifyPassword(String plainPassword, String storedHash) {
        try {
            String[] parts = storedHash.split(":", 2);
            if (parts.length != 2) return false;
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            return computeHash(salt, plainPassword).equals(storedHash);
        } catch (Exception e) {
            return false;
        }
    }

    public User createUser(String username, String plainPassword) throws SQLException {
        String id = UUID.randomUUID().toString();
        String hash = hashPassword(plainPassword);
        String sql = "INSERT INTO authentication (id, username, password_hash) VALUES (?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(id));
            statement.setString(2, username);
            statement.setString(3, hash);
            statement.executeUpdate();
        }
        return new User(id, username);
    }

    public boolean userExists(String username) throws SQLException {
        String sql = "SELECT 1 FROM authentication WHERE username = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public User validateUser(String username, String plainPassword) throws SQLException {
        String sql = "SELECT id, password_hash FROM authentication WHERE username = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                if (!verifyPassword(plainPassword, rs.getString("password_hash"))) return null;
                return new User(rs.getString("id"), username);
            }
        }
    }

    public boolean accountNumberExists(String accountNumber) throws SQLException {
        String sql = "SELECT 1 FROM accounts WHERE account_number = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountNumber);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    public Account createAccount(String accountNumber, String customerName, String phone,
                                 String address, BigDecimal initialBalance) throws SQLException {
        BigDecimal balance = money(initialBalance);
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO accounts (id, account_number, customer_name, phone, address, balance) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(id));
            statement.setString(2, accountNumber);
            statement.setString(3, customerName);
            statement.setString(4, phone);
            statement.setString(5, address);
            statement.setBigDecimal(6, balance);
            statement.executeUpdate();
        }
        Instant now = Instant.now();
        return new Account(id, accountNumber, customerName, phone, address, balance, now, now);
    }

    public Optional<Account> findAccountByNumber(String accountNumber) throws SQLException {
        String sql = "SELECT id, account_number, customer_name, phone, address, balance, created_at, updated_at " +
                "FROM accounts WHERE account_number = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountNumber);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapAccount(rs));
            }
        }
    }

    public List<Account> listAllAccounts() throws SQLException {
        String sql = "SELECT id, account_number, customer_name, phone, address, balance, created_at, updated_at " +
                "FROM accounts ORDER BY created_at DESC";
        List<Account> accounts = new java.util.ArrayList<>();
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    accounts.add(mapAccount(rs));
                }
            }
        }
        return accounts;
    }

    public Optional<BankTransaction> findTransactionByIdempotencyKey(String key) throws SQLException {
        if (key == null || key.isBlank()) return Optional.empty();
        String sql = "SELECT t.id, t.account_id, a.account_number, t.type, t.amount, t.previous_balance, " +
                "t.new_balance, t.performed_by, u.username, t.created_at " +
                "FROM transactions t " +
                "JOIN accounts a ON a.id = t.account_id " +
                "JOIN authentication u ON u.id = t.performed_by " +
                "WHERE t.idempotency_key = ?";
        try (Connection connection = getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapTransaction(rs));
            }
        }
    }

    /**
     * Atomically applies CREDIT or WITHDRAW. Uses SELECT FOR UPDATE so concurrent
     * requests cannot produce an incorrect balance. Rolls back on any failure.
     */
    public BankTransaction applyTransaction(String accountNumber, String type, BigDecimal rawAmount,
                                            String staffUserId, String idempotencyKey) throws SQLException {
        BigDecimal amount = money(rawAmount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        if (!TYPE_CREDIT.equals(type) && !TYPE_WITHDRAW.equals(type)) {
            throw new IllegalArgumentException("Invalid transaction type");
        }

        try (Connection connection = getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<BankTransaction> existing = findTransactionByIdempotencyKey(connection, idempotencyKey);
                if (existing.isPresent()) {
                    connection.commit();
                    return existing.get();
                }

                Account locked = lockAccount(connection, accountNumber);
                if (locked == null) {
                    connection.rollback();
                    throw new AccountNotFoundException(accountNumber);
                }

                BigDecimal previous = money(locked.getBalance());
                BigDecimal next;
                if (TYPE_CREDIT.equals(type)) {
                    next = previous.add(amount);
                } else {
                    if (amount.compareTo(previous) > 0) {
                        connection.rollback();
                        throw new InsufficientFundsException(previous, amount);
                    }
                    next = previous.subtract(amount);
                }
                next = money(next);

                String updateSql = "UPDATE accounts SET balance = ?, updated_at = NOW() WHERE id = ?";
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    update.setBigDecimal(1, next);
                    update.setObject(2, UUID.fromString(locked.getId()));
                    update.executeUpdate();
                }

                String txnId = UUID.randomUUID().toString();
                String insertSql = "INSERT INTO transactions (id, account_id, type, amount, previous_balance, " +
                        "new_balance, performed_by, idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setObject(1, UUID.fromString(txnId));
                    insert.setObject(2, UUID.fromString(locked.getId()));
                    insert.setString(3, type);
                    insert.setBigDecimal(4, amount);
                    insert.setBigDecimal(5, previous);
                    insert.setBigDecimal(6, next);
                    insert.setObject(7, UUID.fromString(staffUserId));
                    if (idempotencyKey == null || idempotencyKey.isBlank()) {
                        insert.setNull(8, java.sql.Types.VARCHAR);
                    } else {
                        insert.setString(8, idempotencyKey);
                    }
                    insert.executeUpdate();
                }

                connection.commit();

                String staffName = lookupUsername(connection, staffUserId);
                return new BankTransaction(
                        txnId, locked.getId(), locked.getAccountNumber(), type,
                        amount, previous, next, staffUserId, staffName, Instant.now()
                );
            } catch (SQLException e) {
                connection.rollback();
                if ("23505".equals(e.getSQLState()) && idempotencyKey != null && !idempotencyKey.isBlank()) {
                    Optional<BankTransaction> raced = findTransactionByIdempotencyKey(connection, idempotencyKey);
                    if (raced.isPresent()) {
                        connection.commit();
                        return raced.get();
                    }
                }
                throw e;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<BankTransaction> listTransactions(String accountNumber, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT t.id, t.account_id, a.account_number, t.type, t.amount, t.previous_balance, " +
                        "t.new_balance, t.performed_by, u.username, t.created_at " +
                        "FROM transactions t " +
                        "JOIN accounts a ON a.id = t.account_id " +
                        "JOIN authentication u ON u.id = t.performed_by "
        );
        if (accountNumber != null && !accountNumber.isBlank()) {
            sql.append("WHERE a.account_number = ? ");
        }
        sql.append("ORDER BY t.created_at DESC LIMIT ?");

        List<BankTransaction> result = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int idx = 1;
            if (accountNumber != null && !accountNumber.isBlank()) {
                statement.setString(idx++, accountNumber);
            }
            statement.setInt(idx, Math.min(Math.max(limit, 1), 200));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(mapTransaction(rs));
                }
            }
        }
        return result;
    }

    private Optional<BankTransaction> findTransactionByIdempotencyKey(Connection connection, String key) throws SQLException {
        if (key == null || key.isBlank()) return Optional.empty();
        String sql = "SELECT t.id, t.account_id, a.account_number, t.type, t.amount, t.previous_balance, " +
                "t.new_balance, t.performed_by, u.username, t.created_at " +
                "FROM transactions t " +
                "JOIN accounts a ON a.id = t.account_id " +
                "JOIN authentication u ON u.id = t.performed_by " +
                "WHERE t.idempotency_key = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapTransaction(rs));
            }
        }
    }

    private Account lockAccount(Connection connection, String accountNumber) throws SQLException {
        String sql = "SELECT id, account_number, customer_name, phone, address, balance, created_at, updated_at " +
                "FROM accounts WHERE account_number = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, accountNumber);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                return mapAccount(rs);
            }
        }
    }

    private String lookupUsername(Connection connection, String userId) throws SQLException {
        String sql = "SELECT username FROM authentication WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(userId));
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString("username") : "";
            }
        }
    }

    private Account mapAccount(ResultSet rs) throws SQLException {
        return new Account(
                rs.getString("id"),
                rs.getString("account_number"),
                rs.getString("customer_name"),
                rs.getString("phone"),
                rs.getString("address"),
                money(rs.getBigDecimal("balance")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private BankTransaction mapTransaction(ResultSet rs) throws SQLException {
        return new BankTransaction(
                rs.getString("id"),
                rs.getString("account_id"),
                rs.getString("account_number"),
                rs.getString("type"),
                money(rs.getBigDecimal("amount")),
                money(rs.getBigDecimal("previous_balance")),
                money(rs.getBigDecimal("new_balance")),
                rs.getString("performed_by"),
                rs.getString("username"),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    public static BigDecimal money(BigDecimal value) {
        if (value == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN);
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String accountNumber) {
            super("Account not found: " + accountNumber);
        }
    }

    public static class InsufficientFundsException extends RuntimeException {
        private final BigDecimal available;
        private final BigDecimal requested;

        public InsufficientFundsException(BigDecimal available, BigDecimal requested) {
            super("Insufficient funds");
            this.available = available;
            this.requested = requested;
        }

        public BigDecimal getAvailable() {
            return available;
        }

        public BigDecimal getRequested() {
            return requested;
        }
    }
}
