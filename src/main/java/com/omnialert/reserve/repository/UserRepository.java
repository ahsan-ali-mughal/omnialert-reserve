package com.omnialert.reserve.repository;

import com.omnialert.reserve.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
