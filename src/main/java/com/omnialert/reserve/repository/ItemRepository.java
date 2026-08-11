package com.omnialert.reserve.repository;

import com.omnialert.reserve.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
}
