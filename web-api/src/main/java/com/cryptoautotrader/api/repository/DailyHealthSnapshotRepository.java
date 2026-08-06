package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.DailyHealthSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DailyHealthSnapshotRepository extends JpaRepository<DailyHealthSnapshotEntity, Long> {

    /** 최신순 이력 조회 (화면·수동 확인용) */
    List<DailyHealthSnapshotEntity> findAllByOrderByCheckedAtDesc(Pageable pageable);
}
