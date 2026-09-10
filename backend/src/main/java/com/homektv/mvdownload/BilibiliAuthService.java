package com.homektv.mvdownload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.homektv.config.SslContextHelper;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.BilibiliAccountDto;
import com.homektv.web.dto.BilibiliPollResultDto;
import com.homektv.web.dto.BilibiliQrCodeDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * 哔哩哔哩扫码登录与鉴权凭据管理服务：
 * 1. 申请登录二维码并在后端通过 ZXing 极速渲染为 Base64 Data URL；
 * 2. 轮询二维码扫码状态 (未扫码 / 已扫码待确认 / 已失效 / 登录成功)；
 * 3. 登录成功自动提取 Set-Cookie (SESSDATA, bili_jct, DedeUserID 等) 并同步用户基本信息与大会员状态；
 * 4. 将脱敏状态与完整凭据持久化至 settings 表 (key=bilibili.auth)，并在内存维护高速缓存供下载与解析直通调用。
 */
@Service
public class BilibiliAuthService {

    private static final Logger log = LoggerFactory.getLogger(BilibiliAuthService.class);
    public static final String SETTING_KEY_AUTH = "bilibili.auth";

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";

    private final SettingRepository settingRepo;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    // 内存高速缓存的完整 Cookie 字符串
    private volatile String cachedCookie;
    // 内存高速缓存的脱敏账号详情
    private volatile BilibiliAccountDto cachedAccount;

    public BilibiliAuthService(SettingRepository settingRepo, ObjectMapper mapper) {
        this.settingRepo = settingRepo;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(SslContextHelper.trustAllSslContext())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    public void init() {
        loadFromStorage();
    }

    /**
     * 从数据库初始化加载持久化凭据
     */
    public synchronized void loadFromStorage() {
        try {
            Optional<Setting> settingOpt = settingRepo.findById(SETTING_KEY_AUTH);
            if (settingOpt.isPresent()) {
                String json = settingOpt.get().getValue();
                if (json != null && !json.isBlank() && !"null".equals(json)) {
                    AuthRecord record = mapper.readValue(json, AuthRecord.class);
                    if (record != null && record.getCookie() != null && !record.getCookie().isBlank()) {
                        this.cachedCookie = record.getCookie();
                        this.cachedAccount = new BilibiliAccountDto(
                                true,
                                record.getMid(),
                                record.getUname(),
                                record.getFace(),
                                record.getVipStatus(),
                                record.getVipType(),
                                record.getVipLabel(),
                                record.getUpdatedAt()
                        );
                        log.info("已加载本地持久化的 B 站账号登录凭据: uid={}, uname={}, vip={}",
                                record.getMid(), record.getUname(), record.getVipLabel());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取本地 B 站登录设置异常: {}", e.getMessage());
        }
        this.cachedCookie = null;
        this.cachedAccount = BilibiliAccountDto.unauthenticated();
    }

    /**
     * 获取当前用于发起 B 站请求的 Cookie 头字符串，未登录或失效时返回 null
     */
    public String getAuthCookie() {
        return this.cachedCookie;
    }

    /**
     * 查询当前账号的登录与会员状态 (前端展示用)
     */
    public BilibiliAccountDto getAccountStatus() {
        if (this.cachedAccount == null) {
            return BilibiliAccountDto.unauthenticated();
        }
        return this.cachedAccount;
    }

    /**
     * 申请 B 站登录二维码及对应 Base64 图片
     */
    public BilibiliQrCodeDto generateQrCode() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://passport.bilibili.com/x/passport-login/web/qrcode/generate"))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ApiException("BILIBILI_AUTH_HTTP_ERROR", "请求 B 站申请二维码接口失败: HTTP " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            if (root.path("code").asInt(-1) != 0) {
                throw new ApiException("BILIBILI_AUTH_API_ERROR", "B 站生成二维码失败: " + root.path("message").asText());
            }

            JsonNode data = root.path("data");
            String url = data.path("url").asText("");
            String qrcodeKey = data.path("qrcode_key").asText("");

            if (url.isBlank() || qrcodeKey.isBlank()) {
                throw new ApiException("BILIBILI_AUTH_PARSE_ERROR", "未能从 B 站响应中获取二维码链接或密钥");
            }

            String qrImgBase64 = renderQrCodeBase64(url, 260);
            return new BilibiliQrCodeDto(qrcodeKey, url, qrImgBase64, 180);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成 B 站登录二维码异常: {}", e.getMessage(), e);
            throw new ApiException("BILIBILI_AUTH_FAILED", "生成 B 站登录二维码失败: " + e.getMessage());
        }
    }

    /**
     * 轮询二维码扫码状态
     *
     * @param qrcodeKey 二维码标识
     * @return 轮询结果 (包含状态码与成功时的账号模型)
     */
    public BilibiliPollResultDto pollQrCode(String qrcodeKey) {
        if (qrcodeKey == null || qrcodeKey.isBlank()) {
            throw new ApiException("INVALID_PARAM", "qrcodeKey 不能为空");
        }

        try {
            String pollUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key="
                    + URLEncoder.encode(qrcodeKey, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create(pollUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ApiException("BILIBILI_AUTH_HTTP_ERROR", "轮询 B 站二维码接口失败: HTTP " + response.statusCode());
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.path("data");
            int pollCode = data.path("code").asInt(-1);
            String message = data.path("message").asText("未知状态");

            if (pollCode == 0) {
                // 登录成功！解析 Set-Cookie 并同步保存凭据
                String refreshToken = data.path("refresh_token").asText("");
                String combinedCookie = extractCookiesFromResponse(response);

                BilibiliAccountDto account = fetchAndPersistProfile(combinedCookie, refreshToken);
                log.info("B 站账号扫码登录成功: uid={}, uname={}", account.mid(), account.uname());
                return new BilibiliPollResultDto(0, "登录成功", account);
            } else if (pollCode == 86101) {
                return new BilibiliPollResultDto(86101, "未扫码", null);
            } else if (pollCode == 86090) {
                return new BilibiliPollResultDto(86090, "二维码已扫码未确认", null);
            } else if (pollCode == 86038) {
                return new BilibiliPollResultDto(86038, "二维码已失效", null);
            } else {
                return new BilibiliPollResultDto(pollCode, message, null);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("轮询 B 站二维码异常: {}", e.getMessage());
            throw new ApiException("BILIBILI_POLL_FAILED", "轮询 B 站扫码状态失败: " + e.getMessage());
        }
    }

    /**
     * 主动刷新/校验当前账号的 Cookie 有效性及最新资料
     */
    public BilibiliAccountDto refreshAccountProfile() {
        String currentCookie = this.cachedCookie;
        if (currentCookie == null || currentCookie.isBlank()) {
            return BilibiliAccountDto.unauthenticated();
        }

        try {
            BilibiliAccountDto refreshed = fetchUserProfile(currentCookie);
            if (refreshed != null && refreshed.isLoggedIn()) {
                // 更新本地持久化记录
                updateProfileInStorage(refreshed);
                return refreshed;
            } else {
                log.warn("B 站账号凭据已在远端失效或未登录，清理凭据");
                logout();
                return BilibiliAccountDto.unauthenticated();
            }
        } catch (Exception e) {
            log.warn("刷新 B 站账号资料异常: {}", e.getMessage());
            return getAccountStatus();
        }
    }

    /**
     * 退出 B 站登录并销毁本地持久化凭据
     */
    @Transactional
    public void logout() {
        try {
            settingRepo.deleteById(SETTING_KEY_AUTH);
        } catch (Exception e) {
            log.warn("从数据库删除 B 站凭据异常: {}", e.getMessage());
        }
        this.cachedCookie = null;
        this.cachedAccount = BilibiliAccountDto.unauthenticated();
        log.info("已清空本地 B 站登录凭据并恢复为未登录态");
    }

    /**
     * 从 HTTP 响应头的所有 Set-Cookie 提取键值，并合并为规范的 Cookie 请求头
     */
    private String extractCookiesFromResponse(HttpResponse<?> response) {
        List<String> setCookies = response.headers().allValues("Set-Cookie");
        Map<String, String> cookieMap = new LinkedHashMap<>();

        for (String line : setCookies) {
            if (line == null || line.isBlank()) continue;
            String[] parts = line.split(";");
            if (parts.length > 0) {
                String[] kv = parts[0].split("=", 2);
                if (kv.length == 2) {
                    cookieMap.put(kv[0].trim(), kv[1].trim());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieMap.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 抓取用户信息并持久化到 settings 表
     */
    private synchronized BilibiliAccountDto fetchAndPersistProfile(String cookie, String refreshToken) {
        BilibiliAccountDto profile = fetchUserProfile(cookie);
        if (profile == null || !profile.isLoggedIn()) {
            profile = new BilibiliAccountDto(true, 0L, "B站用户", "", 0, 0, "普通用户", System.currentTimeMillis());
        }

        AuthRecord record = new AuthRecord(
                cookie,
                refreshToken,
                profile.mid(),
                profile.uname(),
                profile.face(),
                profile.vipStatus(),
                profile.vipType(),
                profile.vipLabel(),
                System.currentTimeMillis()
        );

        try {
            String json = mapper.writeValueAsString(record);
            Setting setting = settingRepo.findById(SETTING_KEY_AUTH).orElseGet(() -> {
                Setting s = new Setting();
                s.setKey(SETTING_KEY_AUTH);
                return s;
            });
            setting.setValue(json);
            settingRepo.save(setting);
        } catch (Exception e) {
            log.error("持久化 B 站凭据至 settings 失败: {}", e.getMessage(), e);
        }

        this.cachedCookie = cookie;
        this.cachedAccount = profile;
        return profile;
    }

    private synchronized void updateProfileInStorage(BilibiliAccountDto profile) {
        try {
            Optional<Setting> settingOpt = settingRepo.findById(SETTING_KEY_AUTH);
            if (settingOpt.isPresent()) {
                AuthRecord oldRecord = mapper.readValue(settingOpt.get().getValue(), AuthRecord.class);
                AuthRecord newRecord = new AuthRecord(
                        oldRecord.getCookie(),
                        oldRecord.getRefreshToken(),
                        profile.mid(),
                        profile.uname(),
                        profile.face(),
                        profile.vipStatus(),
                        profile.vipType(),
                        profile.vipLabel(),
                        System.currentTimeMillis()
                );
                Setting setting = settingOpt.get();
                setting.setValue(mapper.writeValueAsString(newRecord));
                settingRepo.save(setting);
                this.cachedAccount = profile;
            }
        } catch (Exception e) {
            log.warn("更新 settings 中 B 站用户资料异常: {}", e.getMessage());
        }
    }

    /**
     * 请求 B 站 /x/web-interface/nav 查询个人身份与大会员信息
     */
    private BilibiliAccountDto fetchUserProfile(String cookie) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Cookie", cookie)
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if (root.path("code").asInt(-1) == 0) {
                    JsonNode data = root.path("data");
                    boolean isLogin = data.path("isLogin").asBoolean(false);
                    if (isLogin) {
                        long mid = data.path("mid").asLong(0L);
                        String uname = data.path("uname").asText("B站用户");
                        String face = data.path("face").asText("");
                        int vipStatus = data.path("vipStatus").asInt(0);
                        int vipType = data.path("vipType").asInt(0);

                        String vipLabelText = data.path("vip_label").path("text").asText("");
                        if (vipLabelText.isBlank()) {
                            vipLabelText = (vipStatus > 0) ? (vipType == 2 ? "年度大会员" : "大会员") : "普通用户";
                        }

                        return new BilibiliAccountDto(
                                true,
                                mid,
                                uname,
                                face,
                                vipStatus,
                                vipType,
                                vipLabelText,
                                System.currentTimeMillis()
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取 B 站用户信息接口异常: {}", e.getMessage());
        }
        return BilibiliAccountDto.unauthenticated();
    }

    /**
     * 使用 ZXing 将字符串渲染为指定边长的 Base64 PNG 图片
     */
    private String renderQrCodeBase64(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            int black = 0x000000, white = 0xFFFFFF;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    img.setRGB(x, y, matrix.get(x, y) ? black : white);
                }
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            log.error("ZXing 渲染二维码失败: {}", e.getMessage(), e);
            throw new ApiException("QR_RENDER_FAILED", "生成二维码图片失败: " + e.getMessage());
        }
    }

    /**
     * 存储在 settings 表内部的完整凭据载荷结构
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthRecord {
        private String cookie;
        private String refreshToken;
        private Long mid;
        private String uname;
        private String face;
        private int vipStatus;
        private int vipType;
        private String vipLabel;
        private Long updatedAt;

        public AuthRecord() {}

        public AuthRecord(String cookie, String refreshToken, Long mid, String uname, String face,
                          int vipStatus, int vipType, String vipLabel, Long updatedAt) {
            this.cookie = cookie;
            this.refreshToken = refreshToken;
            this.mid = mid;
            this.uname = uname;
            this.face = face;
            this.vipStatus = vipStatus;
            this.vipType = vipType;
            this.vipLabel = vipLabel;
            this.updatedAt = updatedAt;
        }

        public String getCookie() { return cookie; }
        public void setCookie(String cookie) { this.cookie = cookie; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public Long getMid() { return mid; }
        public void setMid(Long mid) { this.mid = mid; }
        public String getUname() { return uname; }
        public void setUname(String uname) { this.uname = uname; }
        public String getFace() { return face; }
        public void setFace(String face) { this.face = face; }
        public int getVipStatus() { return vipStatus; }
        public void setVipStatus(int vipStatus) { this.vipStatus = vipStatus; }
        public int getVipType() { return vipType; }
        public void setVipType(int vipType) { this.vipType = vipType; }
        public String getVipLabel() { return vipLabel; }
        public void setVipLabel(String vipLabel) { this.vipLabel = vipLabel; }
        public Long getUpdatedAt() { return updatedAt; }
        public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
    }
}
