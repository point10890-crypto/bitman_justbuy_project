package com.bitman.justbuy.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionStatus subscription = SubscriptionStatus.FREE;

    private String depositorName;

    private LocalDateTime subscriptionRequestedAt;

    private LocalDate subscriptionEndDate;

    private LocalDateTime subscriptionApprovedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 이 시각 이전에 발급된 토큰은 무효.
     *
     * <p>비밀번호 변경·관리자 초기화 시 갱신한다. 이게 없으면 계정이 털려 비밀번호를
     * 바꿔도 공격자가 이미 들고 있는 토큰이 만료(최대 30일)까지 그대로 통한다.
     * null 이면 검사하지 않는다 — 기존 회원은 다음 비밀번호 변경 때부터 적용된다.
     */
    private LocalDateTime tokenValidFrom;

    protected User() {}

    public User(String email, String name, String passwordHash) {
        this.email = email;
        this.name = name;
        this.passwordHash = passwordHash;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public SubscriptionStatus getSubscription() { return subscription; }
    public void setSubscription(SubscriptionStatus subscription) { this.subscription = subscription; }
    public String getDepositorName() { return depositorName; }
    public void setDepositorName(String depositorName) { this.depositorName = depositorName; }
    public LocalDateTime getSubscriptionRequestedAt() { return subscriptionRequestedAt; }
    public void setSubscriptionRequestedAt(LocalDateTime subscriptionRequestedAt) { this.subscriptionRequestedAt = subscriptionRequestedAt; }
    public LocalDate getSubscriptionEndDate() { return subscriptionEndDate; }
    public void setSubscriptionEndDate(LocalDate subscriptionEndDate) { this.subscriptionEndDate = subscriptionEndDate; }
    public LocalDateTime getSubscriptionApprovedAt() { return subscriptionApprovedAt; }
    public void setSubscriptionApprovedAt(LocalDateTime subscriptionApprovedAt) { this.subscriptionApprovedAt = subscriptionApprovedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getTokenValidFrom() { return tokenValidFrom; }
    public void setTokenValidFrom(LocalDateTime tokenValidFrom) { this.tokenValidFrom = tokenValidFrom; }
}
