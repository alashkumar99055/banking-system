import java.math.BigDecimal;
import java.time.Instant;

public class BankTransaction {
    private String id;
    private String accountId;
    private String accountNumber;
    private String type;
    private BigDecimal amount;
    private BigDecimal previousBalance;
    private BigDecimal newBalance;
    private String performedByUserId;
    private String performedByUsername;
    private Instant createdAt;

    public BankTransaction() {
    }

    public BankTransaction(String id, String accountId, String accountNumber, String type,
                           BigDecimal amount, BigDecimal previousBalance, BigDecimal newBalance,
                           String performedByUserId, String performedByUsername, Instant createdAt) {
        this.id = id;
        this.accountId = accountId;
        this.accountNumber = accountNumber;
        this.type = type;
        this.amount = amount;
        this.previousBalance = previousBalance;
        this.newBalance = newBalance;
        this.performedByUserId = performedByUserId;
        this.performedByUsername = performedByUsername;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPreviousBalance() {
        return previousBalance;
    }

    public BigDecimal getNewBalance() {
        return newBalance;
    }

    public String getPerformedByUserId() {
        return performedByUserId;
    }

    public String getPerformedByUsername() {
        return performedByUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
