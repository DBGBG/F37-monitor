package com.moxa.monitor.repo;

import com.moxa.monitor.domain.InspectionStandard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

/** 点检标准仓库 [B] */
public interface InspectionStandardRepository extends JpaRepository<InspectionStandard, Integer>,
        JpaSpecificationExecutor<InspectionStandard> {

    Optional<InspectionStandard> findByCategoryAndName(String category, String name);
}
