package com.inshot.hx.myhelper.repository;

import com.inshot.hx.myhelper.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    boolean existsByUsername(String username);
}
