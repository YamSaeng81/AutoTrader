package com.cryptoautotrader.api.repository;

import com.cryptoautotrader.api.entity.RulesetSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RulesetSnapshotRepository extends JpaRepository<RulesetSnapshotEntity, String> {
}
