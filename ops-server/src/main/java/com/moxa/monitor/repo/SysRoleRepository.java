package com.moxa.monitor.repo;

import com.moxa.monitor.domain.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 系统角色仓库 [B] (种子数据固定 ADMIN/OPERATOR/VIEWER 三条) */
public interface SysRoleRepository extends JpaRepository<SysRole, Integer> {

    Optional<SysRole> findByCode(String code);

    List<SysRole> findAllByOrderByIdAsc();
}
