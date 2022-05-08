package xyz.xcye.oauth.api.service;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import xyz.xcye.core.entity.R;
import xyz.xcye.data.entity.Condition;

/**
 * 角色权限信息控制器
 * @author qsyyke
 * @date Created in 2022/5/4 22:43
 */

@FeignClient(value = "aurora-admin",name = "aurora-admin", contextId = "authRolePermissionFeignService")
public interface RolePermissionFeignService {


    /**
     * 根据用户名，加载该用于所拥有的角色和角色对应的权限信息，如果此用户不存在，则返回空数组
     * @param username
     * @return
     */
    @GetMapping("/admin/permissionRelation/username/{username}")
    R loadPermissionByUsername(@PathVariable("username") String username);
    //Set<Map<String,String>> loadPermissionByUsername(@PathVariable("username") String username);

    /**
     * 加载所有的角色和权限的关系
     * @param condition
     * @return
     */
    @GetMapping("/admin/permissionRelation/rolePermission")
    R loadAllRolePermission(Condition<Long> condition);
}
