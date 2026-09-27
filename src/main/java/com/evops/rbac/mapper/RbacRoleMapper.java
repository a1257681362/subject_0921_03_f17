package com.evops.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.rbac.entity.RbacRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RbacRoleMapper extends BaseMapper<RbacRole> {

    /** 某用户当前绑定的【启用中】角色 code（多岗并集的角色部分；每请求实时查，停用即时退出）。 */
    @Select("SELECT DISTINCT r.code FROM t_admin_user_role ur "
            + "JOIN t_rbac_role r ON r.id = ur.role_id AND r.status = 'ACTIVE' "
            + "WHERE ur.user_id = #{userId}")
    List<String> selectActiveRoleCodes(@Param("userId") long userId);

    /** 某用户绑定的全部角色（含停用，管理端回显用），按绑定顺序。 */
    @Select("SELECT r.* FROM t_admin_user_role ur "
            + "JOIN t_rbac_role r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId} ORDER BY ur.id")
    List<RbacRole> selectRolesByUserId(@Param("userId") long userId);
}
