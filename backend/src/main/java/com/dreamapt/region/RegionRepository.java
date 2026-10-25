package com.dreamapt.region;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionRepository extends JpaRepository<Region, String> {

    List<Region> findByCollectEnabledTrueOrderBySggCd();
}
