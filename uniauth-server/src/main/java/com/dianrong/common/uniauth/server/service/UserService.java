package com.dianrong.common.uniauth.server.service;

import com.dianrong.common.uniauth.common.bean.InfoName;
import com.dianrong.common.uniauth.common.bean.dto.RoleDto;
import com.dianrong.common.uniauth.common.bean.dto.UserDto;
import com.dianrong.common.uniauth.common.enm.UserActionEnum;
import com.dianrong.common.uniauth.common.util.AuthUtils;
import com.dianrong.common.uniauth.common.util.Base64;
import com.dianrong.common.uniauth.server.data.entity.*;
import com.dianrong.common.uniauth.server.data.mapper.RoleCodeMapper;
import com.dianrong.common.uniauth.server.data.mapper.RoleMapper;
import com.dianrong.common.uniauth.server.data.mapper.UserMapper;
import com.dianrong.common.uniauth.server.data.mapper.UserRoleMapper;
import com.dianrong.common.uniauth.server.exp.AppException;
import com.dianrong.common.uniauth.server.util.AppConstants;
import com.dianrong.common.uniauth.server.util.BeanConverter;
import com.dianrong.common.uniauth.server.util.UniBundle;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * Created by Arc on 14/1/16.
 */
@Service
public class UserService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private UserRoleMapper userRoleMapper;
    @Autowired
    private RoleMapper roleMapper;
    @Autowired
    private RoleCodeMapper roleCodeMapper;

    @Transactional
    public UserDto addNewUser(String name, String phone, String email) {
        this.checkPhoneAndEmail(phone, email, null);
        User user = new User();
        user.setEmail(email);
        user.setName(name);

        Date now = new Date();
        user.setFailCount(AppConstants.ZERO_Byte);

        String randomPassword = AuthUtils.randomPassword();
        byte salt[] = AuthUtils.createSalt();
        user.setPassword(Base64.encode(AuthUtils.digest(randomPassword, salt)));
        user.setPasswordSalt(Base64.encode(salt));

        user.setLastUpdate(now);
        user.setCreateDate(now);
        user.setPasswordDate(now);
        user.setPhone(phone);
        user.setStatus(AppConstants.ZERO_Byte);
        userMapper.insert(user);
        UserDto userDto = BeanConverter.convert(user).setPassword(randomPassword);
        return userDto;
    }

    @Transactional
    public void updateUser(UserActionEnum userActionEnum, Long id, String name, String phone, String email, String password, Byte status) {
        if(userActionEnum == null || id == null) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.parameter.empty", "userActionEnum, userId"));
        }
        User user = userMapper.selectByPrimaryKey(id);
        if(user == null) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.entity.notfound", id, User.class.getSimpleName()));
        } else if(user.getStatus().equals(AppConstants.ONE_Byte) && !UserActionEnum.STATUS_CHANGE.equals(userActionEnum)) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.entity.status.isone", id, User.class.getSimpleName()));
        }
        switch(userActionEnum) {
            case LOCK:
                user.setFailCount(AppConstants.MAX_AUTH_FAIL_COUNT);
                break;
            case UNLOCK:
                user.setFailCount((byte)0);
                break;
            case RESET_PASSWORD:
                if(password == null) {
                    throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.parameter.empty", "password"));
                } else if(!AuthUtils.validatePasswordRule(password)) {
                    throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("user.parameter.password.rule"));
                }
                byte salt[] = AuthUtils.createSalt();
                user.setPassword(Base64.encode(AuthUtils.digest(password, salt)));
                user.setPasswordSalt(Base64.encode(salt));
                user.setPasswordDate(new Date());
                break;
            case STATUS_CHANGE:
                user.setStatus(status);
                break;
            case UPDATE_INFO:
                this.checkPhoneAndEmail(phone, email, id);
                user.setName(name);
                user.setEmail(email);
                user.setPhone(phone);
                user.setLastUpdate(new Date());
                break;
        }
        userMapper.updateByPrimaryKey(user);
    }

    public List<RoleDto> getAllRolesToUser(Long userId, Integer domainId) {
        if(userId == null || domainId == null) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.parameter.empty", "userId, domainId"));
        }
        // 1. get all roles under the domain
        RoleExample roleExample = new RoleExample();
        roleExample.createCriteria().andDomainIdEqualTo(domainId);
        List<Role> roles = roleMapper.selectByExample(roleExample);
        if(CollectionUtils.isEmpty(roles)) {
            return null;
        }
        // 2. get the checked roleIds for the user
        UserRoleExample userRoleExample = new UserRoleExample();
        userRoleExample.createCriteria().andUserIdEqualTo(userId);
        List<UserRoleKey> userRoleKeys = userRoleMapper.selectByExample(userRoleExample);
        Set<Integer> roleIds = null;
        if(!CollectionUtils.isEmpty(userRoleKeys)) {
            roleIds = new TreeSet<>();
            for(UserRoleKey userRoleKey : userRoleKeys) {
                roleIds.add(userRoleKey.getRoleId());
            }
        }

        List<RoleCode> roleCodes = roleCodeMapper.getAllRoleCodes();
        // build roleCode index.
        Map<Integer, String> roleCodeIdNamePairs = new TreeMap<>();
        for(RoleCode roleCode : roleCodes) {
            roleCodeIdNamePairs.put(roleCode.getId(), roleCode.getCode());
        }

        // 3. construct all roles under the domain & mark the role checked on the user or not
        List<RoleDto> roleDtos = new ArrayList<>();
        for(Role role : roles) {
            RoleDto roleDto = new RoleDto()
                    .setId(role.getId())
                    .setName(role.getName())
                    .setRoleCode(roleCodeIdNamePairs.get(role.getRoleCodeId()));
            if(roleIds != null && roleIds.contains(role.getId())) {
                roleDto.setChecked(Boolean.TRUE);
            } else {
                roleDto.setChecked(Boolean.FALSE);
            }
            roleDtos.add(roleDto);
        }
        return roleDtos;
    }

    private void checkPhoneAndEmail(String phone, String email, Long userId) {
        if(email == null) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("common.parameter.empty", "email"));
        }
        // check duplicate email
        UserExample userExample = new UserExample();
        UserExample.Criteria criteria1 = userExample.createCriteria().andEmailEqualTo(email);
        if(userId != null) {
            criteria1.andIdNotEqualTo(userId);
        }
        List<User> emailUsers =  userMapper.selectByExample(userExample);
        if(!CollectionUtils.isEmpty(emailUsers)) {
            throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("user.parameter.email.dup", email));
        }
        if(phone != null) {
            //check duplicate phone
            UserExample userPhoneExample = new UserExample();
            UserExample.Criteria criteria2 = userPhoneExample.createCriteria().andPhoneEqualTo(phone);
            if(userId != null) {
                criteria2.andIdNotEqualTo(userId);
            }
            List<User> phoneUsers = userMapper.selectByExample(userPhoneExample);
            if (!CollectionUtils.isEmpty(phoneUsers)) {
                throw new AppException(InfoName.VALIDATE_FAIL, UniBundle.getMsg("user.parameter.phone.dup", phone));
            }
        }
    }


}
