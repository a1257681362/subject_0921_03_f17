package com.evops.rbac.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.rbac.entity.RbacMenu;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RbacMenuMapper extends BaseMapper<RbacMenu> {

    /**
     * 某用户启用角色并集出的【启用中】菜单/权限点（JOIN 天然去重；多岗共有节点只返回一行）。
     * 角色停用、菜单停用、解绑授权任一发生，下一请求即从结果中消失。
     */
    @Select("SELECT DISTINCT m.* FROM t_admin_user_role ur "
            + "JOIN t_rbac_role r ON r.id = ur.role_id AND r.status = 'ACTIVE' "
            + "JOIN t_rbac_role_menu rm ON rm.role_id = r.id "
            + "JOIN t_rbac_menu m ON m.id = rm.menu_id AND m.status = 'ENABLED' "
            + "WHERE ur.user_id = #{userId} ORDER BY m.sort_no, m.id")
    List<RbacMenu> selectMenusForUser(@Param("userId") long userId);

    /** 全部启用菜单（ADMIN 菜单树使用，避免漏配导致管理员菜单不全）。 */
    @Select("SELECT * FROM t_rbac_menu WHERE status = 'ENABLED' ORDER BY sort_no, id")
    List<RbacMenu> selectAllEnabled();
}
