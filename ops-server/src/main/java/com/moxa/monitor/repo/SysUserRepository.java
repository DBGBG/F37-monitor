package com.moxa.monitor.repo;

import com.moxa.monitor.domain.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 系统用户仓库 [B] */
public interface SysUserRepository extends JpaRepository<SysUser, Integer> {

    Optional<SysUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<SysUser> findAllByOrderByIdAsc();

    List<SysUser> findByRoleCode(String roleCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SysUser u SET u.lastLoginAt = :at WHERE u.id = :id")
    int touchLogin(@Param("id") Integer id, @Param("at") LocalDateTime at);

    /** 改口令单独一条, 避免与显示名/角色更新互相覆盖 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SysUser u SET u.passwordHash = :hash WHERE u.id = :id")
    int updatePassword(@Param("id") Integer id, @Param("hash") String hash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE SysUser u SET u.displayName = :displayName, u.roleCode = :roleCode, u.enabled = :enabled "
            + "WHERE u.id = :id")
    int updateProfile(@Param("id") Integer id, @Param("displayName") String displayName,
                      @Param("roleCode") String roleCode, @Param("enabled") Boolean enabled);
}
