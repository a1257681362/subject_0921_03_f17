package com.evops.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.auth.config.AuthProperties;
import com.evops.auth.dto.AdminProfileVO;
import com.evops.auth.dto.TokenPairVO;
import com.evops.auth.entity.AdminUser;
import com.evops.auth.entity.RefreshToken;
import com.evops.auth.entity.TokenBlacklist;
import com.evops.auth.mapper.AdminUserMapper;
import com.evops.auth.mapper.RefreshTokenMapper;
import com.evops.auth.mapper.TokenBlacklistMapper;
import com.evops.auth.security.JwtClaims;
import com.evops.auth.security.PasswordHasher;
import com.evops.auth.security.TokenGenerator;
import com.evops.auth.web.AuthException;
import com.evops.auth.security.JwtService;
import com.evops.rbac.service.RbacQueryService;
import com.evops.rbac.service.RbacSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 双令牌认证核心。
 *
 * 安全约定（内部审计整改 auth-1）：
 * 1. 访问令牌短有效期（15min），吊销靠黑名单 + token_version 双通道；
 * 2. 刷新令牌只在响应里出现一次明文，库存 SHA-256；
 * 3. 刷新一次性：消费即 USED，旧令牌立刻失效，新令牌沿同家族轮换；
 *    轮换同时把配对的旧访问令牌按 jti 拉黑（reason=ROTATED）——刷新完成的一刻
 *    旧 access 立即作废，不允许新旧两枚 access 在剩余有效期内并行使用；
 * 4. 已 USED/REVOKED 的令牌再次使用 = 疑似泄露，同家族 ACTIVE 令牌全部连坐撤销；
 * 5. 登出拉黑当前访问令牌（并撤销本设备刷新令牌）；
 * 6. 改密：token_version 自增使全体旧访问令牌失效、撤销全部刷新令牌、拉黑当前访问令牌——全端下线；
 * 7. 停用账号：status=DISABLED（当请求即被挡）+ token_version 自增 + 撤销全部刷新令牌，
 *    重新启用后停用前签发的旧令牌也不复活，必须重新登录。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AdminUserMapper userMapper;
    private final RefreshTokenMapper refreshMapper;
    private final TokenBlacklistMapper blacklistMapper;
    private final PasswordHasher passwordHasher;
    private final TokenGenerator tokenGenerator;
    private final JwtService jwtService;
    private final AuthProperties props;
    /** 一人多岗：每请求实时装载启用角色并集与权限码并集 */
    private final RbacQueryService rbacQueryService;
    /** 安全撤销必须独立落库：即使外层刷新事务因抛 401 回滚，连坐撤销也不能回滚掉 */
    private final TransactionTemplate requiresNewTx;

    public AuthService(AdminUserMapper userMapper, RefreshTokenMapper refreshMapper,
                       TokenBlacklistMapper blacklistMapper, PasswordHasher passwordHasher,
                       TokenGenerator tokenGenerator, JwtService jwtService, AuthProperties props,
                       RbacQueryService rbacQueryService,
                       PlatformTransactionManager transactionManager) {
        this.userMapper = userMapper;
        this.refreshMapper = refreshMapper;
        this.blacklistMapper = blacklistMapper;
        this.passwordHasher = passwordHasher;
        this.tokenGenerator = tokenGenerator;
        this.jwtService = jwtService;
        this.props = props;
        this.rbacQueryService = rbacQueryService;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ==================== 登录 ====================

    public TokenPairVO login(String username, String password) {
        LambdaQueryWrapper<AdminUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdminUser::getUsername, username);
        AdminUser user = userMapper.selectOne(wrapper);
        // 用户名不存在与密码错误统一文案，防止账号探测
        if (user == null || !passwordHasher.matches(password, user.getPasswordHash())) {
            throw AuthException.unauthorized("用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw AuthException.unauthorized("账号已停用，请联系管理员");
        }
        // 每次登录开一条新的设备家族
        return issuePair(user, tokenGenerator.randomId());
    }

    // ==================== 刷新（一次性轮换 + 重放连坐） ====================

    @Transactional(rollbackFor = Exception.class)
    public TokenPairVO refresh(String rawRefreshToken) {
        String tokenHash = tokenGenerator.sha256Hex(rawRefreshToken);
        RefreshToken row = refreshMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, tokenHash));
        if (row == null) {
            throw AuthException.unauthorized("刷新令牌无效，请重新登录");
        }
        if (row.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw AuthException.unauthorized("刷新令牌已过期，请重新登录");
        }

        AdminUser user = userMapper.selectById(row.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw AuthException.unauthorized("账号不可用，请重新登录");
        }
        if (user.getTokenVersion() == null || !user.getTokenVersion().equals(row.getTokenVersion())) {
            // 改密后旧家族签发的刷新令牌整体作废；独立事务提交，不随本次 401 回滚
            LocalDateTime revokeTime = LocalDateTime.now();
            String familyId = row.getFamilyId();
            requiresNewTx.executeWithoutResult(s ->
                    refreshMapper.revokeFamilyActive(familyId, revokeTime));
            throw AuthException.unauthorized("登录态已失效，请重新登录");
        }

        LocalDateTime now = LocalDateTime.now();
        int consumed = refreshMapper.consume(tokenHash, now);
        if (consumed == 0) {
            // 令牌已被消费或撤销却再次出现：重放。按家族连坐并记录安全事件。
            // 撤销走独立事务：必须先落库，再抛 401，避免随外层事务回滚导致连坐失效
            String currentStatus = row.getStatus();
            String familyId = row.getFamilyId();
            Integer revoked = requiresNewTx.execute(s ->
                    refreshMapper.revokeFamilyActive(familyId, now));
            log.warn("刷新令牌重放，疑似泄露：userId={}, familyId={}, rowStatus={}, 连坐撤销{}枚",
                    row.getUserId(), familyId, currentStatus, revoked);
            throw AuthException.unauthorized("刷新令牌已失效，请重新登录");
        }

        // 旧刷新令牌 ACTIVE→USED 成功（影响行数 0 走重放分支）。
        // 同一事务内把与该 refresh 配对的旧访问令牌按 jti 拉黑：
        // 一旦 refresh 成功返回，旧 access 在下个请求即失效，不能与新 access 并行使用。
        revokePairedAccess(row, now);

        // 旧令牌已 USED 立即失效；新令牌沿同家族签发，构成轮换链
        return issuePair(user, row.getFamilyId());
    }

    /**
     * 轮换消费成功后作废旧访问令牌：按配对 jti 落黑名单（reason=ROTATED）。
     * V8 之前的存量刷新令牌没有配对登记（accessJti 为空），无法定位旧 access，
     * 跳过拉黑、等其 15 分钟自然过期收敛；已自然过期的也不必再落库。
     */
    private void revokePairedAccess(RefreshToken consumed, LocalDateTime now) {
        String jti = consumed.getAccessJti();
        if (jti == null || jti.trim().isEmpty()) {
            return;
        }
        LocalDateTime accessExpiresAt = consumed.getAccessExpiresAt();
        if (accessExpiresAt != null && !accessExpiresAt.isAfter(now)) {
            return;
        }
        TokenBlacklist blocked = new TokenBlacklist();
        blocked.setJti(jti);
        blocked.setUserId(consumed.getUserId());
        blocked.setReason(TokenBlacklist.REASON_ROTATED);
        // 登记行缺失过期时间的极端情况下，按访问令牌 TTL 兜底黑名单存活时长
        blocked.setExpiresAt(accessExpiresAt != null
                ? accessExpiresAt : now.plusSeconds(props.getAccessTokenTtlSeconds()));
        try {
            blacklistMapper.insert(blocked);
        } catch (DuplicateKeyException ignored) {
            // 该 jti 已因登出/改密在黑名单：幂等，轮换照常成功
        }
    }

    // ==================== 登出 ====================

    @Transactional(rollbackFor = Exception.class)
    public void logout(JwtClaims access, String rawRefreshToken) {
        blacklistAccess(access, TokenBlacklist.REASON_LOGOUT);

        if (rawRefreshToken != null && !rawRefreshToken.trim().isEmpty()) {
            RefreshToken row = refreshMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                    .eq(RefreshToken::getTokenHash, tokenGenerator.sha256Hex(rawRefreshToken.trim())));
            // 只允许撤销本人令牌；非 ACTIVE 行幂等跳过
            if (row != null && row.getUserId() == access.getUserId()) {
                refreshMapper.revokeById(row.getId(), LocalDateTime.now());
            }
        }
    }

    // ==================== 改密（全端下线） ====================

    @Transactional(rollbackFor = Exception.class)
    public AdminProfileVO changePassword(JwtClaims access, String oldPassword, String newPassword) {
        AdminUser user = userMapper.selectById(access.getUserId());
        if (user == null) {
            throw AuthException.unauthorized("账号不存在");
        }
        if (!passwordHasher.matches(oldPassword, user.getPasswordHash())) {
            throw AuthException.badRequest("原密码不正确");
        }
        if (passwordHasher.matches(newPassword, user.getPasswordHash())) {
            throw AuthException.badRequest("新密码不能与原密码相同");
        }

        // 1) 改密并推进令牌版本——所有旧 tv 的访问令牌立即不被信任
        user.setPasswordHash(passwordHasher.hash(newPassword));
        user.setTokenVersion(user.getTokenVersion() == null ? 1 : user.getTokenVersion() + 1);
        user.setUpdateTime(LocalDateTime.now());
        int rows = userMapper.updateById(user);
        if (rows == 0) {
            throw new IllegalStateException("账号更新失败，请重试");
        }

        // 2) 撤销全部设备的在用刷新令牌
        int revoked = refreshMapper.revokeUserActive(user.getId(), LocalDateTime.now());

        // 3) 当前这枚访问令牌也立刻拉黑（其它设备的访问令牌由 tv 不匹配拦截，无需逐枚落黑名单）
        blacklistAccess(access, TokenBlacklist.REASON_PASSWORD_CHANGE);

        log.warn("改密完成，全端下线：userId={}, 撤销刷新令牌{}枚", user.getId(), revoked);
        return toProfile(user);
    }

    // ==================== 账号停用/启用（管理端） ====================

    /**
     * 停用账号：三管齐下保证令牌立即失效——
     * 1) status=DISABLED：authenticate/refresh/login 下一请求即按状态拒绝（无需等待令牌过期）；
     * 2) token_version 自增：即使日后重新启用，停用前签发的访问令牌仍因 tv 不匹配继续作废，旧会话不复活；
     * 3) 撤销该账号全部设备的 ACTIVE 刷新令牌，停用期间无法再轮换出新令牌。
     * 返回停用后的账号状态视图。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUser deactivateAccount(long targetUserId) {
        AdminUser user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw AuthException.badRequest("账号不存在：id=" + targetUserId);
        }
        if ("DISABLED".equals(user.getStatus())) {
            return user; // 幂等：重复停用无副作用
        }
        LocalDateTime now = LocalDateTime.now();
        user.setStatus("DISABLED");
        user.setTokenVersion(user.getTokenVersion() == null ? 1 : user.getTokenVersion() + 1);
        user.setUpdateTime(now);
        userMapper.updateById(user);
        int revoked = refreshMapper.revokeUserActive(user.getId(), now);
        log.warn("账号停用，在线令牌即时失效：userId={}, username={}, 撤销刷新令牌{}枚",
                user.getId(), user.getUsername(), revoked);
        return user;
    }

    /**
     * 重新启用账号：只恢复可登录状态。停用前签发的旧访问/刷新令牌
     * 因停用时已推进 tv 并撤销刷新令牌，不会随启用复活，必须重新登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public AdminUser activateAccount(long targetUserId) {
        AdminUser user = userMapper.selectById(targetUserId);
        if (user == null) {
            throw AuthException.badRequest("账号不存在：id=" + targetUserId);
        }
        if ("ACTIVE".equals(user.getStatus())) {
            return user; // 幂等
        }
        user.setStatus("ACTIVE");
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        log.warn("账号重新启用（历史令牌仍作废，需重新登录）：userId={}, username={}",
                user.getId(), user.getUsername());
        return user;
    }

    // ==================== 访问令牌校验（过滤器调用） ====================

    /**
     * 校验访问令牌：签名/有效期 -> 未拉黑 -> 账号有效且 tv 匹配。任一不过抛 401。
     */
    public JwtClaims authenticate(String accessToken) {
        JwtClaims claims = jwtService.verify(accessToken);

        TokenBlacklist blocked = blacklistMapper.selectById(claims.getJti());
        if (blocked != null) {
            throw AuthException.unauthorized("登录态已失效，请重新登录");
        }

        AdminUser user = userMapper.selectById(claims.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw AuthException.unauthorized("账号不可用，请重新登录");
        }
        if (user.getTokenVersion() == null || user.getTokenVersion() != claims.getTokenVersion()) {
            throw AuthException.unauthorized("登录态已失效，请重新登录");
        }
        // 主岗位按库内最新值回填（脱敏白名单主角色判定）；角色调整即时生效，不必重签令牌
        claims.setRole(user.getRole());
        // 一人多岗：启用角色集 + 权限码并集每请求实时装载，
        // 停用角色/解绑授权下一请求即收缩（旧 JWT 照样被挡，无需用户重登）
        RbacSnapshot snapshot = rbacQueryService.loadSnapshot(user.getId());
        claims.setRoles(snapshot.getRoles());
        claims.setPermissions(snapshot.getPermissions());
        return claims;
    }

    // ==================== 内部 ====================

    private TokenPairVO issuePair(AdminUser user, String familyId) {
        int tv = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        String jti = tokenGenerator.randomId();
        String accessToken = jwtService.issueAccess(user.getId(), user.getUsername(), tv, jti);
        long accessExpEpoch = jwtService.peekExpEpoch(accessToken);

        String rawRefresh = tokenGenerator.randomToken();
        RefreshToken record = new RefreshToken();
        record.setTokenHash(tokenGenerator.sha256Hex(rawRefresh));
        record.setUserId(user.getId());
        record.setFamilyId(familyId);
        record.setTokenVersion(tv);
        record.setStatus(RefreshToken.ACTIVE);
        record.setExpiresAt(LocalDateTime.now().plusSeconds(props.getRefreshTokenTtlSeconds()));
        // 登记配对访问令牌：本刷新令牌被一次性轮换消费时，按此把旧 access 立即拉黑
        record.setAccessJti(jti);
        record.setAccessExpiresAt(LocalDateTime.ofInstant(
                Instant.ofEpochSecond(accessExpEpoch), ZoneId.systemDefault()));
        refreshMapper.insert(record);

        TokenPairVO vo = new TokenPairVO();
        vo.setAccessToken(accessToken);
        vo.setRefreshToken(rawRefresh);
        vo.setTokenType("Bearer");
        vo.setAccessTokenExpiresIn(props.getAccessTokenTtlSeconds());
        vo.setRefreshTokenExpiresIn(props.getRefreshTokenTtlSeconds());
        vo.setUser(toProfile(user));
        return vo;
    }

    private void blacklistAccess(JwtClaims access, String reason) {
        TokenBlacklist row = new TokenBlacklist();
        row.setJti(access.getJti());
        row.setUserId(access.getUserId());
        row.setReason(reason);
        row.setExpiresAt(LocalDateTime.ofInstant(
                Instant.ofEpochSecond(access.getExp()), ZoneId.systemDefault()));
        try {
            blacklistMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 重复登出幂等
        }
    }

    private AdminProfileVO toProfile(AdminUser user) {
        AdminProfileVO vo = new AdminProfileVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setDisplayName(user.getDisplayName());
        vo.setVenue(user.getVenue());
        vo.setRole(user.getRole());
        // 登录/刷新/查资料响应都带最新岗位集合（并集）
        vo.setRoles(new java.util.ArrayList<>(rbacQueryService.loadSnapshot(user.getId()).getRoles()));
        return vo;
    }

    /** 供 /me 查询当前账号信息。 */
    public AdminProfileVO profile(long userId) {
        AdminUser user = userMapper.selectById(userId);
        if (user == null) {
            throw AuthException.unauthorized("账号不存在");
        }
        return toProfile(user);
    }
}
