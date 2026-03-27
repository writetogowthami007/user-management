package com.example.usermanagementservice.mapper;

import com.example.usermanagementservice.dto.user.CreateUserRequest;
import com.example.usermanagementservice.dto.user.UserResponse;
import com.example.usermanagementservice.model.User;
import org.mapstruct.Mapping;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "active", ignore = true)
    User toEntity(CreateUserRequest request);

    UserResponse toResponse(User user);
}
