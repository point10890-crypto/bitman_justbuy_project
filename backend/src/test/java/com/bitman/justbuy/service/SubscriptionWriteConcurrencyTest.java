package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.UserDto;
import com.bitman.justbuy.dto.AuthRequest;
import com.bitman.justbuy.controller.ApiException;
import com.bitman.justbuy.entity.SubscriptionPlan;
import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import com.bitman.justbuy.entity.Role;
import com.bitman.justbuy.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/** Real transactions and H2 rows; only external authentication/notification dependencies are absent. */
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:subscription-write-concurrency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "bitman.admin.email="
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SubscriptionService.class, AuthService.class, MemberExpiryNoticeService.class,
    SubscriptionWriteConcurrencyTest.RepositoryObservation.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubscriptionWriteConcurrencyTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final AtomicReference<Consumer<String>> READ_OBSERVER = new AtomicReference<>(method -> {});

    @Autowired UserRepository repository;
    @Autowired SubscriptionService subscriptions;
    @Autowired AuthService auth;
    @Autowired MemberExpiryNoticeService notices;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PasswordEncoder encoder;
    @MockitoBean JwtService jwtService;
    @MockitoBean TelegramNotifier notifier;
    private ExecutorService workers;

    @BeforeEach
    void setUp() {
        READ_OBSERVER.set(method -> {});
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        READ_OBSERVER.set(method -> {});
        workers.shutdownNow();
        assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        repository.deleteAll();
    }

    @Test
    void concurrentApplicationCannotReplaceTheFirstAcceptedPlanOrPayer() throws Exception {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(7));
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> transaction().executeWithoutResult(status -> {
            subscriptions.applyForSubscription(member.getId(), "six-month payer", SubscriptionPlan.SIX_MONTHS);
            firstApplied.countDown();
            await(commitFirst);
        }));
        await(firstApplied);

        CountDownLatch contenderRead = new CountDownLatch(1);
        CountDownLatch continueContender = new CountDownLatch(1);
        observeContenderRead(contenderRead, continueContender);
        Future<?> contender = workers.submit(() -> {
            Thread.currentThread().setName("subscription-contender");
            return subscriptions.applyForSubscription(member.getId(), "monthly payer", SubscriptionPlan.MONTHLY);
        });
        // A plain SELECT reaches this observer before the first transaction commits; a locked SELECT waits.
        contenderRead.await(2, TimeUnit.SECONDS);
        commitFirst.countDown();
        try {
            first.get(10, TimeUnit.SECONDS);
        } finally {
            continueContender.countDown();
        }

        assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class);
        User saved = repository.findById(member.getId()).orElseThrow();
        assertThat(saved.getSubscription()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(saved.getRequestedSubscriptionPlan()).isEqualTo(SubscriptionPlan.SIX_MONTHS);
        assertThat(saved.getDepositorName()).isEqualTo("six-month payer");
        assertThat(saved.getSubscriptionEndDate()).isEqualTo(member.getSubscriptionEndDate());
    }

    @Test
    void profileUpdateCannotRestoreAccessRevokedByAnotherTransaction() throws Exception {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(7));
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch commitRevocation = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> transaction().executeWithoutResult(status -> {
            subscriptions.revokeSubscription(member.getId());
            revoked.countDown();
            await(commitRevocation);
        }));
        await(revoked);

        CountDownLatch contenderRead = new CountDownLatch(1);
        CountDownLatch continueContender = new CountDownLatch(1);
        observeContenderRead(contenderRead, continueContender);
        Future<UserDto> contender = workers.submit(() -> {
            Thread.currentThread().setName("subscription-contender");
            return auth.updateProfile(member.getId(), "Updated member");
        });
        contenderRead.await(2, TimeUnit.SECONDS);
        commitRevocation.countDown();
        try {
            first.get(10, TimeUnit.SECONDS);
        } finally {
            continueContender.countDown();
        }
        assertThat(contender.get(10, TimeUnit.SECONDS).name()).isEqualTo("Updated member");

        User saved = repository.findById(member.getId()).orElseThrow();
        assertThat(saved.getSubscription()).isEqualTo(SubscriptionStatus.FREE);
        assertThat(saved.getSubscriptionEndDate()).isEqualTo(LocalDate.now(KST));
        assertThat(subscriptions.isActivePro(saved)).isFalse();
        assertThat(saved.getName()).isEqualTo("Updated member");
    }

    @Test
    void expirySweepRechecksAUserApprovedAfterItsCandidateListWasRead() throws Exception {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).minusDays(1));
        CountDownLatch candidatesRead = new CountDownLatch(1);
        CountDownLatch continueSweep = new CountDownLatch(1);
        READ_OBSERVER.set(method -> {
            if (Thread.currentThread().getName().equals("expiry-sweep") && method.equals("findExpiredProUserIds")) {
                candidatesRead.countDown();
                await(continueSweep);
            }
        });
        Future<?> sweep = workers.submit(() -> {
            Thread.currentThread().setName("expiry-sweep");
            return subscriptions.expireSubscriptionsNow();
        });
        await(candidatesRead);
        try {
            subscriptions.applyForSubscription(member.getId(), "renewal payer", SubscriptionPlan.SIX_MONTHS);
            subscriptions.approveSubscription(member.getId());
        } finally {
            continueSweep.countDown();
        }
        sweep.get(10, TimeUnit.SECONDS);

        User saved = repository.findById(member.getId()).orElseThrow();
        assertThat(saved.getSubscription()).isEqualTo(SubscriptionStatus.PRO);
        assertThat(saved.getSubscriptionPlan()).isEqualTo(SubscriptionPlan.SIX_MONTHS);
        assertThat(saved.getSubscriptionEndDate()).isEqualTo(LocalDate.now(KST).plusMonths(6));
        assertThat(subscriptions.isActivePro(saved)).isTrue();
    }

    @Test
    void ordinaryCurrentUserReadDoesNotWaitForAProfileWriteLock() throws Exception {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(7));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commitUpdate = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> transaction().executeWithoutResult(status -> {
            auth.updateProfile(member.getId(), "Updated member");
            updated.countDown();
            await(commitUpdate);
        }));
        await(updated);
        try {
            Future<UserDto> read = workers.submit(() -> auth.getCurrentUser(member.getId()));
            assertThat(read.get(2, TimeUnit.SECONDS).name()).isEqualTo("member");
        } finally {
            commitUpdate.countDown();
        }
        first.get(10, TimeUnit.SECONDS);
    }

    @Test
    void adminRepairRefreshesProfileCommittedWhileTheRepairWasWaitingForItsLock() throws Exception {
        User member = saveMember(SubscriptionStatus.PENDING, LocalDate.now(KST).plusDays(7));
        transaction().executeWithoutResult(status -> {
            User current = repository.findById(member.getId()).orElseThrow();
            current.setRole(Role.ADMIN);
            repository.saveAndFlush(current);
        });
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commitUpdate = new CountDownLatch(1);
        Future<?> first = workers.submit(() -> transaction().executeWithoutResult(status -> {
            auth.updateProfile(member.getId(), "Updated admin");
            updated.countDown();
            await(commitUpdate);
        }));
        await(updated);
        CountDownLatch initialRead = new CountDownLatch(1);
        READ_OBSERVER.set(method -> {
            if (Thread.currentThread().getName().equals("admin-repair") && method.equals("findById")) {
                initialRead.countDown();
            }
        });
        Future<UserDto> repair = workers.submit(() -> {
            Thread.currentThread().setName("admin-repair");
            return auth.getCurrentUser(member.getId());
        });
        await(initialRead);
        commitUpdate.countDown();
        first.get(10, TimeUnit.SECONDS);
        UserDto repaired = repair.get(10, TimeUnit.SECONDS);

        assertThat(repaired.name()).isEqualTo("Updated admin");
        assertThat(repaired.subscription()).isEqualTo("PRO");
        assertThat(repaired.subscriptionEndDate()).isNull();
        assertThat(repository.findById(member.getId()).orElseThrow().getName()).isEqualTo("Updated admin");
    }

    @Test
    void expiryNoticeRechecksTelegramUnlinkAfterItsCandidateListWasRead() throws Exception {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(3));
        transaction().executeWithoutResult(status -> {
            User current = repository.findById(member.getId()).orElseThrow();
            current.setTelegramChatId("123456789");
            repository.saveAndFlush(current);
        });
        CountDownLatch candidatesRead = new CountDownLatch(1);
        CountDownLatch continueNotice = new CountDownLatch(1);
        READ_OBSERVER.set(method -> {
            if (Thread.currentThread().getName().equals("expiry-notice")
                    && method.equals("findExpiryNoticeTargetIds")) {
                candidatesRead.countDown();
                await(continueNotice);
            }
        });
        Future<?> notice = workers.submit(() -> {
            Thread.currentThread().setName("expiry-notice");
            return notices.run(LocalDate.now(KST));
        });
        await(candidatesRead);
        try {
            subscriptions.unlinkTelegram(member.getId());
        } finally {
            continueNotice.countDown();
        }
        notice.get(10, TimeUnit.SECONDS);

        assertThat(repository.findById(member.getId()).orElseThrow().getTelegramChatId()).isNull();
        verify(notifier, never()).sendToMember(anyString(), anyString());
    }

    @Test
    void passwordResetCommittedAfterCredentialCheckRejectsTheOldPasswordBeforeTokenIssuance() throws Exception {
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder();
        User member = saveCredentialMember(realEncoder, "OldPassword123!");
        CountDownLatch credentialChecked = new CountDownLatch(1);
        CountDownLatch continueLogin = new CountDownLatch(1);
        pauseAfterInitialCredentialCheck(realEncoder, credentialChecked, continueLogin);
        when(encoder.encode(anyString())).thenAnswer(invocation -> realEncoder.encode(invocation.getArgument(0)));
        when(jwtService.generateToken(any(), anyString(), anyString(), anyBoolean())).thenReturn("issued-test-token");
        Future<?> login = workers.submit(() -> {
            Thread.currentThread().setName("credential-login");
            return auth.login(new AuthRequest(member.getEmail(), "OldPassword123!", true));
        });
        await(credentialChecked);
        try {
            subscriptions.adminResetPassword(member.getId(), "NewPassword456!");
            User reset = repository.findById(member.getId()).orElseThrow();
            assertThat(realEncoder.matches("NewPassword456!", reset.getPasswordHash())).isTrue();
        } finally {
            continueLogin.countDown();
        }

        assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error.getCause()).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(jwtService, never()).generateToken(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void memberDeletedAfterCredentialCheckCannotReceiveAToken() throws Exception {
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder();
        User member = saveCredentialMember(realEncoder, "OldPassword123!");
        CountDownLatch credentialChecked = new CountDownLatch(1);
        CountDownLatch continueLogin = new CountDownLatch(1);
        pauseAfterInitialCredentialCheck(realEncoder, credentialChecked, continueLogin);
        when(jwtService.generateToken(any(), anyString(), anyString(), anyBoolean())).thenReturn("issued-test-token");
        Future<?> login = workers.submit(() -> {
            Thread.currentThread().setName("credential-login");
            return auth.login(new AuthRequest(member.getEmail(), "OldPassword123!", true));
        });
        await(credentialChecked);
        try {
            transaction().executeWithoutResult(status -> repository.deleteById(member.getId()));
            assertThat(repository.findById(member.getId())).isEmpty();
        } finally {
            continueLogin.countDown();
        }

        assertThatThrownBy(() -> login.get(10, TimeUnit.SECONDS))
            .hasCauseInstanceOf(ApiException.class)
            .satisfies(error -> assertThat(((ApiException) error.getCause()).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(jwtService, never()).generateToken(any(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void changedHashWithTheSamePasswordIsRecheckedAndStillLogsIn() throws Exception {
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder();
        User member = saveCredentialMember(realEncoder, "OldPassword123!");
        CountDownLatch credentialChecked = new CountDownLatch(1);
        CountDownLatch continueLogin = new CountDownLatch(1);
        pauseAfterInitialCredentialCheck(realEncoder, credentialChecked, continueLogin);
        when(encoder.encode(anyString())).thenAnswer(invocation -> realEncoder.encode(invocation.getArgument(0)));
        when(jwtService.generateToken(any(), anyString(), anyString(), anyBoolean())).thenReturn("issued-test-token");
        Future<?> login = workers.submit(() -> {
            Thread.currentThread().setName("credential-login");
            return auth.login(new AuthRequest(member.getEmail(), "OldPassword123!", true));
        });
        await(credentialChecked);
        try {
            subscriptions.adminResetPassword(member.getId(), "OldPassword123!");
        } finally {
            continueLogin.countDown();
        }
        assertThat(login.get(10, TimeUnit.SECONDS)).isInstanceOf(com.bitman.justbuy.dto.AuthResponse.class);
        verify(encoder, times(2)).matches(anyString(), anyString());
        verify(jwtService).generateToken(any(), anyString(), anyString(), anyBoolean());
    }

    private User saveCredentialMember(BCryptPasswordEncoder encoder, String password) {
        User member = saveMember(SubscriptionStatus.PRO, LocalDate.now(KST).plusDays(7));
        transaction().executeWithoutResult(status -> {
            User current = repository.findById(member.getId()).orElseThrow();
            current.setPasswordHash(encoder.encode(password));
            repository.saveAndFlush(current);
        });
        return member;
    }

    private void pauseAfterInitialCredentialCheck(BCryptPasswordEncoder realEncoder,
                                                  CountDownLatch checked, CountDownLatch continueLogin) {
        AtomicInteger checks = new AtomicInteger();
        when(encoder.matches(anyString(), anyString())).thenAnswer(invocation -> {
            boolean valid = realEncoder.matches(invocation.getArgument(0), invocation.getArgument(1));
            if (Thread.currentThread().getName().equals("credential-login") && checks.incrementAndGet() == 1) {
                checked.countDown();
                await(continueLogin);
            }
            return valid;
        });
    }

    private User saveMember(SubscriptionStatus subscription, LocalDate end) {
        return transaction().execute(status -> {
            User member = new User(UUID.randomUUID() + "@test.local", "member", "hash");
            member.setSubscription(subscription);
            member.setSubscriptionPlan(SubscriptionPlan.MONTHLY);
            member.setSubscriptionEndDate(end);
            member.setSubscriptionApprovedAt(LocalDateTime.now(KST).minusMonths(1));
            return repository.saveAndFlush(member);
        });
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static void observeContenderRead(CountDownLatch read, CountDownLatch continueMutation) {
        READ_OBSERVER.set(method -> {
            if (Thread.currentThread().getName().equals("subscription-contender")
                    && (method.equals("findById") || method.equals("findByIdForUpdate"))) {
                read.countDown();
                await(continueMutation);
            }
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for transaction barrier");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for transaction barrier", e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RepositoryObservation {
        @Bean
        static BeanPostProcessor observeRealRepositoryReads() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof UserRepository)) return bean;
                    return Proxy.newProxyInstance(UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                        (proxy, method, args) -> {
                            Object result;
                            try {
                                result = method.invoke(bean, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                            READ_OBSERVER.get().accept(method.getName());
                            return result;
                        });
                }
            };
        }
    }
}
