package com.bitman.justbuy.repository;

import com.bitman.justbuy.entity.SubscriptionStatus;
import com.bitman.justbuy.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailIgnoreCase(String email);

    Optional<User> findFirstByEmailIgnoreCaseOrderByCreatedAtAsc(String email);

    List<User> findAllByEmailIgnoreCase(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findBySubscription(SubscriptionStatus subscription);

    /** 만료일이 지난 PRO 유저 목록 (자동 다운그레이드용) */
    @Query("SELECT u FROM User u WHERE u.subscription = 'PRO' AND u.role <> 'ADMIN' AND u.subscriptionEndDate < :today")
    List<User> findExpiredProUsers(@Param("today") LocalDate today);

    /** 특정 기간 내 만료 예정인 PRO 유저 수 */
    @Query("SELECT COUNT(u) FROM User u WHERE u.subscription = 'PRO' AND u.subscriptionEndDate BETWEEN :from AND :to")
    long countExpiringBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 만료 예고 대상 — 지정한 종료일들을 가진 활성 PRO 회원 중 텔레그램을 연결한 사람.
     * 관리자는 만료 개념이 없으므로 제외한다.
     */
    @Query("SELECT u FROM User u WHERE u.subscription = 'PRO' AND u.role <> 'ADMIN' "
         + "AND u.telegramChatId IS NOT NULL AND u.subscriptionEndDate IN :dates")
    List<User> findExpiryNoticeTargets(@Param("dates") List<LocalDate> dates);

    /** 특정 날짜 이후 생성된 유저 수 */
    @Query("SELECT COUNT(u) FROM User u WHERE u.createdAt >= :since")
    long countCreatedSince(@Param("since") java.time.LocalDateTime since);
}
