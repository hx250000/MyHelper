package com.inshot.hx.myhelper.dto.request;

/** 不使用 record，避免默认 toString 将明文密码写入日志。 */
public class RegisterRequest {

    private String username;
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
