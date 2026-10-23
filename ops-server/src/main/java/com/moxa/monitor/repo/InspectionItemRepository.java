package com.moxa.monitor.repo;

import com.moxa.monitor.domain.InspectionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/** 点检计划项仓库 [B] */
public interface InspectionItemRepository extends JpaRepository<InspectionItem, Integer> {

    List<InspectionItem> findByPlanIdOrderBySortOrderAscIdAsc(Integer planId);

    List<InspectionItem> findByPlanIdInOrderBySortOrderAscIdAsc(List<Integer> planIds);

    /** 计划列表的 item_count */
    long countByPlanId(Integer planId);

    /** 计划更新采用"先清空再写入", 避免残留旧项 */
    @Modifying
    void deleteByPlanId(Integer planId);

    /** 标准库的 ref_count: 按计划项名称汇总引用次数, 避免逐条 COUNT */
    @Query("SELECT i.name, COUNT(i.name) FROM InspectionItem i GROUP BY i.name")
    List<Object[]> countGroupByName();
}
