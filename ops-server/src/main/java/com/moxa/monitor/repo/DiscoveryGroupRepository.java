package com.moxa.monitor.repo;

import com.moxa.monitor.domain.DiscoveryGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 设备发现范围仓库 [B写/C读] */
public interface DiscoveryGroupRepository extends JpaRepository<DiscoveryGroup, Integer> {

    List<DiscoveryGroup> findAllByOrderByIdAsc();

    List<DiscoveryGroup> findByEnabledIsTrueOrderByIdAsc();
}
