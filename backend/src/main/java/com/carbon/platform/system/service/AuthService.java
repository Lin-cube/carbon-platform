package com.carbon.platform.system.service;

import com.carbon.platform.system.dto.LoginRequest;
import com.carbon.platform.system.dto.LoginResponse;
import com.carbon.platform.system.dto.UserInfoVO;

public interface AuthService {

    LoginResponse login(LoginRequest request);

    UserInfoVO currentUserInfo();
}
