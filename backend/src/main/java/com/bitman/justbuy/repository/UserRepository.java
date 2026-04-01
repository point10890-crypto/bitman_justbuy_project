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

    boolean existsByEmail(String email);

    List<User> findBySubscription(SubscriptionStatus subscription);

    /** 만료일이 지난 PRO 유저 목록 (자동 다운그레이드용) */
    @Query("SELECT u FROM User u WHERE u.subscription = 'PRO' AND u.subscriptionEndDate < :today")
    List<User> findExpiredProUsers(@Param("today") LocalDate today);
}
