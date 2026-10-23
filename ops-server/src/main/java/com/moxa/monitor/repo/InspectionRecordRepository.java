package com.moxa.monitor.repo;

import com.moxa.monitor.domain.InspectionRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 点检执行明细仓库 [B] */
public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Integer> {

    List<InspectionRecord> findByTaskIdOrderByIdAsc(Integer taskId);

    /** 点检履历: 批量取回本页任务的明细, 避免 N+1 */
    List<InspectionRecord> findByTaskIdInOrderByIdAsc(List<Integer> taskIds);

    /** 点检履历: 最近 N 条明细 (联表任务名由服务层补齐) */
    List<InspectionRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Modifying
    void deleteByTaskIdIn(List<Integer> taskIds);

    /** 保留策略: 随任务一起清理 */
    @Modifying
    @Query(value = "DELETE FROM inspection_records WHERE created_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
