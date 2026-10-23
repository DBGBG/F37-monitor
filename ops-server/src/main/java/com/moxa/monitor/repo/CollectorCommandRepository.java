package com.moxa.monitor.repo;

import com.moxa.monitor.domain.CollectorCommand;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 采集指令仓库 [B写/C读] */
public interface CollectorCommandRepository extends JpaRepository<CollectorCommand, Integer> {

    List<CollectorCommand> findByStatusOrderByIdAsc(String status);

    List<CollectorCommand> findTop50ByOrderByIdDesc();

    List<CollectorCommand> findAllByOrderByIdDesc(Pageable pageable);

    /** 指令是否仍在排队/执行, 用于前端展示"下发中" */
    long countByStatusIn(List<String> statuses);

    /** 采集层执行完后回写状态与结果 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CollectorCommand c SET c.status = :status, c.result = :result, c.executedAt = :at WHERE c.id = :id")
    int markExecuted(@Param("id") Integer id, @Param("status") String status,
                     @Param("result") String result, @Param("at") LocalDateTime at);
}
