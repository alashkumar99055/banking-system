import java.math.BigDecimal;
import java.time.Instant;

public class Account {
    private String id;
    private String accountNumber;
    private String customerName;
    private String phone;
    private String address;
    private BigDecimal balance;
    private Instant createdAt;
    private Instant updatedAt;

    public Account() {
    }

    public Account(String id, String accountNumber, String customerName, String phone,
                   String address, BigDecimal balance, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.customerName = customerName;
        this.phone = phone;
        this.address = address;
        this.balance = balance;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getPhone() {
        return phone;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
