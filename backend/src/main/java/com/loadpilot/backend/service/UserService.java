package com.loadpilot.backend.service;

import com.loadpilot.backend.dto.response.UserSummaryResponse;
import com.loadpilot.backend.enums.AppRole;
import java.util.List;

public interface UserService {

    List<UserSummaryResponse> list();

    UserSummaryResponse updateRole(String userId, AppRole role);

    UserSummaryResponse updateStatus(String userId, boolean enabled);
}
