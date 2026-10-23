package com.moxa.monitor.repo;

import com.moxa.monitor.domain.SysUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** 用户-角色关联仓库 [B] */
public interface SysUserRoleRepository extends JpaRepository<SysUserRole, SysUserRole.Pk> {

    List<SysUserRole> findByUserId(Integer userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SysUserRole r WHERE r.userId = :userId")
    int deleteByUserId(@Param("userId") Integer userId);
}
