package com.fraud.backend.data.repository.accessmanagement;

import com.fraud.backend.data.entity.accessmanagement.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, Long> {
    
    Optional<UserSession> findBySessionId(String sessionId);
    
    Optional<UserSession> findByKeycloakSessionId(String keycloakSessionId);
    
    List<UserSession> findByUserIdAndIsActiveTrue(Long userId);
    
    @Query("SELECT s FROM UserSession s WHERE s.user.id = :userId AND s.isActive = true ORDER BY s.loginTime DESC")
    List<UserSession> findActiveSessionsByUserId(Long userId);
    
    @Query("SELECT s FROM UserSession s WHERE s.lastActivity < :cutoffTime AND s.isActive = true")
    List<UserSession> findStaleActiveSessions(@Param("cutoffTime") LocalDateTime cutoffTime);
    
    void deleteByUserIdAndIsActiveFalse(Long userId);
    
    List<UserSession> findAllByOrderByLoginTimeDesc();
    
    List<UserSession> findByUserIdOrderByLoginTimeDesc(Long userId);
    
    List<UserSession> findByLoginTimeAfter(LocalDateTime time);
    
    @Query("SELECT COUNT(s) FROM UserSession s WHERE s.isActive = true")
    long countActiveSessions();
    
    @Query("SELECT COUNT(s) FROM UserSession s WHERE s.lastActivity > :time")
    long countSessionsActiveAfter(@Param("time") LocalDateTime time);
    
    @Query("SELECT s.user.id, COUNT(s) FROM UserSession s WHERE s.isActive = true GROUP BY s.user.id")
    List<Object[]> countActiveSessionsPerUser();

    @Modifying
    @Query("DELETE FROM UserSession s WHERE s.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);
    
}