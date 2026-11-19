package com.inshot.hx.myhelper.service;

import com.inshot.hx.myhelper.common.api.ResultCode;
import com.inshot.hx.myhelper.common.exception.BusinessException;
import com.inshot.hx.myhelper.dto.request.RegisterRequest;
import com.inshot.hx.myhelper.dto.response.RegisterResponse;
import com.inshot.hx.myhelper.entity.User;
import com.inshot.hx.myhelper.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public RegisterResponse register(RegisterRequest registerRequest) {
        String username = registerRequest.getUsername();
        String password = registerRequest.getPassword();
        if (username == null || username.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名不能为空");
        }
        String normalizedUsername = username.strip();
        if (normalizedUsername.length() > 64) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户名不能超过 64 个字符");
        }
        if (password == null || password.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码不能为空");
        }
        if (password.codePointCount(0, password.length()) < 8) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码至少需要 8 个字符");
        }
        // BCrypt 上限按 UTF-8 字节计算，不能截断后再哈希；密码不做 strip/trim。
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码不能超过 72 个 UTF-8 字节");
        }
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
        }

        byte[] idBytes = new byte[12];
        RANDOM.nextBytes(idBytes);
        User user = new User(Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes),
                normalizedUsername);
        user.setPasswordHash(passwordEncoder.encode(password));
        try {
            // 立即执行 SQL，让唯一约束异常在此处抛出，并确保响应中的时间已初始化。
            return RegisterResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            // register 不开启外层事务；仓库保存事务已回滚，可安全查询。
            // 并发同名注册由唯一索引兜底。
            // 其他完整性异常不伪装成“用户名已存在”。
            if (userRepository.existsByUsername(normalizedUsername)) {
                throw new BusinessException(ResultCode.CONFLICT, "用户名已存在");
            }
            throw exception;
        }
    }

}
