package com.evops.rbac.service;

import com.evops.rbac.dto.MenuNodeVO;
import com.evops.rbac.dto.MenuSnapshotVO;
import com.evops.rbac.entity.RbacMenu;
import com.evops.rbac.mapper.RbacMenuMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 菜单树装配：把用户多岗并集出的菜单行按 parent_id 组树。
 *
 * <p>规则：
 * <ul>
 *   <li>多岗共有节点只出现一次（SQL DISTINCT 已去重，这里按 id 兜底）；</li>
 *   <li>visible=1 的节点才进可见菜单树；权限码（含 visible=0 权限点）全部进扁平权限集；</li>
 *   <li>组内按 sort_no、id 升序；</li>
 *   <li>父节点缺失或被停用的悬挂节点一律丢弃，不提升为根（避免越权露出无入口的功能）。</li>
 * </ul>
 */
@Service
public class MenuTreeAssembler {

    private final RbacQueryService queryService;
    private final RbacMenuMapper menuMapper;

    public MenuTreeAssembler(RbacQueryService queryService, RbacMenuMapper menuMapper) {
        this.queryService = queryService;
        this.menuMapper = menuMapper;
    }

    /** 当前登录人：可见菜单树 + 扁平权限码并集。 */
    public MenuSnapshotVO assembleForUser(long userId) {
        RbacSnapshot snapshot = queryService.loadSnapshot(userId);
        List<RbacMenu> granted = queryService.loadGrantedMenus(userId, snapshot.getRoles());

        MenuSnapshotVO vo = new MenuSnapshotVO();
        vo.setPermissions(new ArrayList<>(new TreeSet<>(snapshot.getPermissions())));
        vo.setMenus(buildTree(granted, true));
        return vo;
    }

    /** 管理端维护用全量树（含 BUTTON / visible=0 / 停用节点）。 */
    public List<MenuNodeVO> assembleAllForAdmin() {
        return buildTree(menuMapper.selectList(null), false);
    }

    private List<MenuNodeVO> buildTree(List<RbacMenu> rows, boolean visibleOnly) {
        // id 去重 + 可选 visible 过滤
        Map<Long, MenuNodeVO> nodes = new HashMap<>();
        for (RbacMenu m : rows) {
            if (visibleOnly && (m.getVisible() == null || m.getVisible() != 1)) {
                continue;
            }
            if (nodes.containsKey(m.getId())) {
                continue;
            }
            nodes.put(m.getId(), toNode(m));
        }

        List<MenuNodeVO> roots = new ArrayList<>();
        for (MenuNodeVO node : nodes.values()) {
            Long parentId = node.getParentId();
            if (parentId == null || parentId == 0L) {
                roots.add(node);
            } else {
                MenuNodeVO parent = nodes.get(parentId);
                // 父不在可见集合（缺失/停用/不可见）：悬挂节点丢弃，不提升为根
                if (parent != null) {
                    parent.getChildren().add(node);
                }
            }
        }
        sortRecursively(roots);
        return roots;
    }

    private void sortRecursively(List<MenuNodeVO> nodes) {
        nodes.sort(Comparator.comparing(MenuNodeVO::getSortNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MenuNodeVO::getId));
        for (MenuNodeVO node : nodes) {
            if (!node.getChildren().isEmpty()) {
                sortRecursively(node.getChildren());
            }
        }
    }

    private MenuNodeVO toNode(RbacMenu m) {
        MenuNodeVO vo = new MenuNodeVO();
        vo.setId(m.getId());
        vo.setParentId(m.getParentId());
        vo.setType(m.getType());
        vo.setName(m.getName());
        vo.setPermission(m.getPermission());
        vo.setPath(m.getPath());
        vo.setIcon(m.getIcon());
        vo.setSortNo(m.getSortNo());
        vo.setVisible(m.getVisible());
        return vo;
    }
}
