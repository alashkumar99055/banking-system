import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {
    private int port;
    private final Path workspaceRoot;
    private final Path staticDir;
    private final Database database;

    private final ConcurrentHashMap<String, String> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> sessionUserId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionExpiry = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();

    private static final long SESSION_TTL_MS = 8 * 60 * 60 * 1000L;
    private static final int LOGIN_RATE_LIMIT = 10;
    private static final long LOGIN_RATE_WINDOW_MS = 60_000L;

    private static final Pattern ACCOUNT_NUMBER_PATTERN = Pattern.compile("^[A-Za-z0-9-]{6,32}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+?[0-9]{10,15}$");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("^\\d+(\\.\\d{1,2})?$");

    public Server(int port) throws SQLException {
        this.port = port;
        this.workspaceRoot = resolveWorkspaceRoot();
        this.staticDir = resolveStaticDir();
        this.database = createDatabase();
    }

    public int start() throws IOException {
        HttpServer httpServer = null;
        int triedPort = port;

        while (true) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress("0.0.0.0", triedPort), 0);
                break;
            } catch (BindException exception) {
                if (System.getenv("PORT") != null || triedPort >= port + 10) {
                    throw exception;
                }
                triedPort++;
            }
        }

        this.port = triedPort;
        httpServer.createContext("/api/login", this::handleLogin);
        httpServer.createContext("/api/register", this::handleRegister);
        httpServer.createContext("/api/logout", this::handleLogout);
        httpServer.createContext("/api/me", this::handleMe);
        httpServer.createContext("/api/health", this::handleHealth);
        httpServer.createContext("/api/accounts", this::handleAccounts);
        httpServer.createContext("/api/transactions", this::handleTransactions);
        httpServer.createContext("/", this::handleRoot);
        httpServer.setExecutor(null);
        httpServer.start();
        return this.port;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String createSession(String username, String userId) {
        String token = generateToken();
        sessions.put(token, username);
        sessionUserId.put(token, userId);
        sessionExpiry.put(token, System.currentTimeMillis() + SESSION_TTL_MS);
        return token;
    }

    private void invalidateSession(String token) {
        sessions.remove(token);
        sessionUserId.remove(token);
        sessionExpiry.remove(token);
    }

    private String extractToken(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        String token = authHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private boolean isSessionValid(String token) {
        if (token == null) return false;
        Long expiry = sessionExpiry.get(token);
        if (expiry == null || System.currentTimeMillis() > expiry) {
            invalidateSession(token);
            return false;
        }
        return sessions.containsKey(token);
    }

    private String getAuthenticatedUser(HttpExchange exchange) {
        String token = extractToken(exchange);
        return isSessionValid(token) ? sessions.get(token) : null;
    }

    private String getAuthenticatedUserId(HttpExchange exchange) {
        String token = extractToken(exchange);
        return isSessionValid(token) ? sessionUserId.get(token) : null;
    }

    private boolean tooManyLoginAttempts(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> attempts = loginAttempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty() && now - attempts.peekFirst() > LOGIN_RATE_WINDOW_MS) {
                attempts.pollFirst();
            }
            if (attempts.size() >= LOGIN_RATE_LIMIT) {
                return true;
            }
            attempts.addLast(now);
            return false;
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        sendJson(exchange, 200, "{\"status\":\"ok\"}");
    }

    private void handleRegister(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String body = readBody(exchange);
        Map<String, String> payload = parseJsonObject(body);
        String username = payload.getOrDefault("username", "").trim();
        String password = payload.getOrDefault("password", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Username and password are required\"}");
            return;
        }
        if (username.length() < 3) {
            sendJson(exchange, 400, "{\"error\":\"Username must be at least 3 characters\"}");
            return;
        }
        if (password.length() < 6) {
            sendJson(exchange, 400, "{\"error\":\"Password must be at least 6 characters\"}");
            return;
        }

        try {
            if (database.userExists(username)) {
                sendJson(exchange, 409, "{\"error\":\"Username is already taken\"}");
                return;
            }
            User user = database.createUser(username, password);
            String token = createSession(user.getUsername(), user.getId());
            sendJson(exchange, 201, authJson(token, user.getUsername()));
        } catch (SQLException e) {
            sendInternalServerError(exchange, e);
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String rateKey = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        if (tooManyLoginAttempts(rateKey)) {
            sendJson(exchange, 429, "{\"error\":\"Too many login attempts. Please wait and try again.\"}");
            return;
        }

        String body = readBody(exchange);
        Map<String, String> payload = parseJsonObject(body);
        String username = payload.getOrDefault("username", "").trim();
        String password = payload.getOrDefault("password", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Username and password are required\"}");
            return;
        }

        try {
            User user = database.validateUser(username, password);
            if (user == null) {
                sendJson(exchange, 401, "{\"error\":\"Invalid username or password\"}");
                return;
            }
            String token = createSession(user.getUsername(), user.getId());
            sendJson(exchange, 200, authJson(token, user.getUsername()));
        } catch (SQLException e) {
            sendInternalServerError(exchange, e);
        }
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String token = extractToken(exchange);
        if (token != null) {
            invalidateSession(token);
        }
        sendJson(exchange, 200, "{\"status\":\"logged out\"}");
    }

    private void handleMe(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        String username = getAuthenticatedUser(exchange);
        if (username == null) {
            sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }
        sendJson(exchange, 200, "{\"username\":\"" + escapeJson(username) + "\"}");
    }

    private void handleAccounts(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;

        String userId = getAuthenticatedUserId(exchange);
        if (userId == null) {
            sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");

        try {
            if ("POST".equals(method) && "/api/accounts".equals(path)) {
                createAccount(exchange);
                return;
            }
            if ("GET".equals(method) && "/api/accounts".equals(path)) {
                lookupAccount(exchange);
                return;
            }
            if ("POST".equals(method) && segments.length == 5 && "credit".equals(segments[4])) {
                applyMoney(exchange, userId, Database.TYPE_CREDIT, decode(segments[3]));
                return;
            }
            if ("POST".equals(method) && segments.length == 5 && "withdraw".equals(segments[4])) {
                applyMoney(exchange, userId, Database.TYPE_WITHDRAW, decode(segments[3]));
                return;
            }
            sendJson(exchange, 404, "{\"error\":\"Not found\"}");
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                sendJson(exchange, 409, "{\"error\":\"Account number already exists\"}");
                return;
            }
            sendInternalServerError(exchange, e);
        }
    }

    private void createAccount(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> payload = parseJsonObject(readBody(exchange));
        String accountNumber = payload.getOrDefault("accountNumber", "").trim();
        String customerName = payload.getOrDefault("customerName", "").trim();
        String phone = payload.getOrDefault("phone", "").trim().replaceAll("[\\s()-]", "");
        String address = payload.getOrDefault("address", "").trim();
        String balanceRaw = payload.getOrDefault("initialBalance", "").trim();

        if (accountNumber.isEmpty() || customerName.isEmpty() || phone.isEmpty() || address.isEmpty() || balanceRaw.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Account number, customer name, phone, address, and initial balance are required\"}");
            return;
        }
        if (!ACCOUNT_NUMBER_PATTERN.matcher(accountNumber).matches()) {
            sendJson(exchange, 400, "{\"error\":\"Account number must be 6–32 letters, digits, or hyphens\"}");
            return;
        }
        if (customerName.length() < 2 || customerName.length() > 120) {
            sendJson(exchange, 400, "{\"error\":\"Customer name must be between 2 and 120 characters\"}");
            return;
        }
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            sendJson(exchange, 400, "{\"error\":\"Phone number must be 10–15 digits, optionally starting with +\"}");
            return;
        }
        if (address.length() > 500) {
            sendJson(exchange, 400, "{\"error\":\"Address is too long\"}");
            return;
        }

        BigDecimal initialBalance;
        try {
            initialBalance = parseMoney(balanceRaw);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
            sendJson(exchange, 400, "{\"error\":\"Initial balance cannot be negative\"}");
            return;
        }

        if (database.accountNumberExists(accountNumber)) {
            sendJson(exchange, 409, "{\"error\":\"Account number already exists\"}");
            return;
        }

        Account account = database.createAccount(accountNumber, customerName, phone, address, initialBalance);
        sendJson(exchange, 201, toJson(account));
    }

    private void lookupAccount(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String accountNumber = query.getOrDefault("accountNumber", "").trim();
        if (accountNumber.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"accountNumber query parameter is required\"}");
            return;
        }
        Optional<Account> account = database.findAccountByNumber(accountNumber);
        if (account.isEmpty()) {
            sendJson(exchange, 404, "{\"error\":\"Account not found\"}");
            return;
        }
        sendJson(exchange, 200, toJson(account.get()));
    }

    private void applyMoney(HttpExchange exchange, String staffUserId, String type, String accountNumber)
            throws IOException, SQLException {
        if (accountNumber == null || accountNumber.isBlank()) {
            sendJson(exchange, 400, "{\"error\":\"Account number is required\"}");
            return;
        }

        Map<String, String> payload = parseJsonObject(readBody(exchange));
        String amountRaw = payload.getOrDefault("amount", "").trim();
        if (amountRaw.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Amount is required\"}");
            return;
        }

        BigDecimal amount;
        try {
            amount = parseMoney(amountRaw);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            sendJson(exchange, 400, "{\"error\":\"Amount must be greater than zero\"}");
            return;
        }

        String idempotencyKey = firstNonBlank(
                exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                payload.get("idempotencyKey")
        );

        try {
            BankTransaction txn = database.applyTransaction(accountNumber, type, amount, staffUserId, idempotencyKey);
            Optional<Account> account = database.findAccountByNumber(accountNumber);
            StringBuilder json = new StringBuilder("{");
            json.append("\"message\":\"").append(Database.TYPE_CREDIT.equals(type)
                    ? "Credit successful" : "Withdrawal successful").append("\",");
            json.append("\"transaction\":").append(toJson(txn)).append(",");
            json.append("\"account\":").append(account.map(this::toJson).orElse("null"));
            json.append("}");
            sendJson(exchange, 200, json.toString());
        } catch (Database.AccountNotFoundException e) {
            sendJson(exchange, 404, "{\"error\":\"Account not found\"}");
        } catch (Database.InsufficientFundsException e) {
            sendJson(exchange, 400, "{\"error\":\"Insufficient funds\",\"available\":\"" +
                    moneyString(e.getAvailable()) + "\",\"requested\":\"" + moneyString(e.getRequested()) + "\"}");
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void handleTransactions(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        if (getAuthenticatedUserId(exchange) == null) {
            sendJson(exchange, 401, "{\"error\":\"Unauthorized\"}");
            return;
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String accountNumber = query.getOrDefault("accountNumber", "").trim();
        int limit = 50;
        try {
            if (query.containsKey("limit")) {
                limit = Integer.parseInt(query.get("limit"));
            }
        } catch (NumberFormatException ignored) {
            limit = 50;
        }

        try {
            if (!accountNumber.isEmpty() && database.findAccountByNumber(accountNumber).isEmpty()) {
                sendJson(exchange, 404, "{\"error\":\"Account not found\"}");
                return;
            }
            List<BankTransaction> items = database.listTransactions(accountNumber.isEmpty() ? null : accountNumber, limit);
            StringBuilder output = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) output.append(",");
                output.append(toJson(items.get(i)));
            }
            output.append("]");
            sendJson(exchange, 200, output.toString());
        } catch (SQLException e) {
            sendInternalServerError(exchange, e);
        }
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (preflight(exchange)) return;
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.startsWith("/api/")) {
            sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            return;
        }

        if (staticDir != null && Files.isDirectory(staticDir)) {
            Path file = resolveSafeStaticFile(requestPath);
            if (file != null && Files.isRegularFile(file)) {
                serveFile(exchange, file);
                return;
            }
            Path index = staticDir.resolve("index.html");
            if ("/".equals(requestPath) || requestPath.isEmpty()) {
                Path login = staticDir.resolve("login.html");
                if (Files.isRegularFile(login)) {
                    serveFile(exchange, login);
                    return;
                }
            }
            if (Files.isRegularFile(index) && !requestPath.contains(".")) {
                serveFile(exchange, index);
                return;
            }
        }

        sendHtml(exchange, 200, "<!DOCTYPE html><html><body><h1>Banking system backend is running</h1></body></html>");
    }

    private Path resolveSafeStaticFile(String requestPath) {
        String relative = requestPath;
        if (relative == null || relative.isEmpty() || "/".equals(relative)) {
            return staticDir.resolve("login.html");
        }
        if (relative.startsWith("/")) relative = relative.substring(1);
        Path resolved = staticDir.resolve(relative).normalize();
        if (!resolved.startsWith(staticDir)) return null;
        return resolved;
    }

    private void serveFile(HttpExchange exchange, Path file) throws IOException {
        String contentType = contentType(file.getFileName().toString());
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private String contentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".html")) return "text/html; charset=UTF-8";
        if (lower.endsWith(".css")) return "text/css; charset=UTF-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (lower.endsWith(".json")) return "application/json; charset=UTF-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private String authJson(String token, String username) {
        return "{\"token\":\"" + escapeJson(token) + "\",\"username\":\"" + escapeJson(username) + "\"}";
    }

    private String toJson(Account account) {
        return "{\"id\":\"" + escapeJson(account.getId()) +
                "\",\"accountNumber\":\"" + escapeJson(account.getAccountNumber()) +
                "\",\"customerName\":\"" + escapeJson(account.getCustomerName()) +
                "\",\"phone\":\"" + escapeJson(account.getPhone()) +
                "\",\"address\":\"" + escapeJson(account.getAddress()) +
                "\",\"balance\":\"" + moneyString(account.getBalance()) +
                "\",\"createdAt\":\"" + escapeJson(iso(account.getCreatedAt())) +
                "\",\"updatedAt\":\"" + escapeJson(iso(account.getUpdatedAt())) + "\"}";
    }

    private String toJson(BankTransaction txn) {
        return "{\"id\":\"" + escapeJson(txn.getId()) +
                "\",\"accountId\":\"" + escapeJson(txn.getAccountId()) +
                "\",\"accountNumber\":\"" + escapeJson(txn.getAccountNumber()) +
                "\",\"type\":\"" + escapeJson(txn.getType()) +
                "\",\"amount\":\"" + moneyString(txn.getAmount()) +
                "\",\"previousBalance\":\"" + moneyString(txn.getPreviousBalance()) +
                "\",\"newBalance\":\"" + moneyString(txn.getNewBalance()) +
                "\",\"performedBy\":\"" + escapeJson(txn.getPerformedByUsername()) +
                "\",\"createdAt\":\"" + escapeJson(iso(txn.getCreatedAt())) + "\"}";
    }

    private String iso(Instant instant) {
        return instant == null ? "" : instant.toString();
    }

    private String moneyString(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private BigDecimal parseMoney(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Amount is required");
        }
        String cleaned = raw.trim().replace(",", "");
        if (!AMOUNT_PATTERN.matcher(cleaned).matches()) {
            throw new IllegalArgumentException("Amount must be a valid non-negative number with up to 2 decimal places");
        }
        BigDecimal value = new BigDecimal(cleaned);
        if (value.scale() > 2) {
            throw new IllegalArgumentException("Amount cannot have more than 2 decimal places");
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private Map<String, String> parseJsonObject(String body) {
        Map<String, String> values = new HashMap<>();
        if (body == null || body.isBlank()) return values;
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|(-?\\d+(?:\\.\\d+)?))")
                .matcher(body);
        while (matcher.find()) {
            if (matcher.group(2) != null) {
                values.put(matcher.group(1), unescapeJson(matcher.group(2)));
            } else if (matcher.group(3) != null) {
                values.put(matcher.group(1), matcher.group(3));
            }
        }
        return values;
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return values;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                values.put(decode(pair), "");
            } else {
                values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return null;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private String unescapeJson(String value) {
        return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isDirectory(current.resolve("frontend")) && Files.isDirectory(current.resolve("backend"))) {
            return current;
        }
        Path parent = current.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("frontend")) && Files.isDirectory(parent.resolve("backend"))) {
            return parent;
        }
        return current;
    }

    private Path resolveStaticDir() {
        String configured = System.getenv("FRONTEND_DIR");
        if (configured != null && !configured.isBlank()) {
            Path path = Paths.get(configured).toAbsolutePath();
            if (Files.isDirectory(path)) return path;
        }
        Path fromRoot = workspaceRoot.resolve("frontend");
        if (Files.isDirectory(fromRoot)) return fromRoot;
        Path sibling = Paths.get(System.getProperty("user.dir")).resolve("frontend");
        if (Files.isDirectory(sibling)) return sibling;
        return fromRoot;
    }

    private boolean preflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) return false;
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private void addCorsHeaders(HttpExchange exchange) {
        String origin = System.getenv("FRONTEND_URL");
        if (origin == null || origin.isBlank()) {
            origin = "*";
        }
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    private void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
        exchange.close();
    }

    private void sendHtml(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response);
        }
        exchange.close();
    }

    private void sendInternalServerError(HttpExchange exchange, Throwable error) throws IOException {
        String body = "{\"error\":\"Internal server error\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(500, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
        error.printStackTrace();
    }

    private Database createDatabase() throws SQLException {
        String url = System.getenv("POSTGRES_URL");
        String user = System.getenv("POSTGRES_USER");
        String pass = System.getenv("POSTGRES_PASSWORD");

        if (url == null || url.isEmpty()) {
            url = System.getenv("DATABASE_URL");
        }

        if ((url == null || url.isEmpty()) && System.getenv("PGHOST") != null) {
            String host = System.getenv("PGHOST");
            String dbPort = System.getenv().getOrDefault("PGPORT", "5432");
            String db = System.getenv().getOrDefault("PGDATABASE", "banking_system");
            user = System.getenv().getOrDefault("PGUSER", user != null ? user : "postgres");
            pass = System.getenv().getOrDefault("PGPASSWORD", pass != null ? pass : "postgres");
            url = "jdbc:postgresql://" + host + ":" + dbPort + "/" + db;
        }

        if (url == null || url.isEmpty()) {
            url = "jdbc:postgresql://localhost:5432/banking_system";
        }
        if (user == null || user.isEmpty()) user = "postgres";
        if (pass == null || pass.isEmpty()) pass = "postgres";

        if (url.startsWith("jdbc:postgresql://") && !url.contains("sslmode")
                && !url.contains("localhost") && !url.contains("127.0.0.1")) {
            url += (url.contains("?") ? "&" : "?") + "sslmode=require";
        }

        if ((url.startsWith("postgres://") || url.startsWith("postgresql://")) && !url.startsWith("jdbc:")) {
            try {
                URI dbUri = new URI(url);
                if (dbUri.getUserInfo() != null) {
                    String[] userInfo = dbUri.getUserInfo().split(":", 2);
                    if (!userInfo[0].isEmpty()) user = userInfo[0];
                    if (userInfo.length > 1 && !userInfo[1].isEmpty()) pass = userInfo[1];
                }
                int dbPort = dbUri.getPort() == -1 ? 5432 : dbUri.getPort();
                String query = dbUri.getQuery();
                if (query == null || query.isBlank()) {
                    query = "sslmode=require";
                }
                url = "jdbc:postgresql://" + dbUri.getHost() + ":" + dbPort + dbUri.getPath() + "?" + query;
            } catch (URISyntaxException e) {
                throw new SQLException("Invalid DATABASE_URL format", e);
            }
        }

        return new Database(url, user, pass);
    }
}
