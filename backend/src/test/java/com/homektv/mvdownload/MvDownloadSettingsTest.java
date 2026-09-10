package com.homektv.mvdownload;

import com.homektv.ai.AiConfigService;
import com.homektv.ai.SecretCryptoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.MvDownloadTask;
import com.homektv.domain.Setting;
import com.homektv.domain.SongFile;
import com.homektv.library.SettingService;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.MvDownloadTaskRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.repo.SongFileRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.MvDownloadSubmitRequest;
import com.homektv.web.dto.MvDownloadTaskDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;

/**
 * MV 下载与双轨伴奏配置迁移至基础配置单元测试
 */
public class MvDownloadSettingsTest {

    private SettingService settingService;
    private MvDownloadService mvDownloadService;
    private final List<MvDownloadTask> savedTasks = new ArrayList<>();
   private final Map<String, Setting> settingsDb = new HashMap<>();

    public MvDownloadSettingsTest() {
        setUp();
    }

   @BeforeEach
   void setUp() {
        savedTasks.clear();
        settingsDb.clear();

        SettingRepository settingRepo = (SettingRepository) Proxy.newProxyInstance(
                SettingRepository.class.getClassLoader(),
                new Class<?>[]{SettingRepository.class},
                (proxy, method, args) -> {
                    if ("findAll".equals(method.getName())) return new ArrayList<>(settingsDb.values());
                    if ("findById".equals(method.getName())) return Optional.ofNullable(settingsDb.get(args[0]));
                    if ("save".equals(method.getName())) {
                        Setting s = (Setting) args[0];
                        settingsDb.put(s.getKey(), s);
                        return s;
                    }
                    return null;
                });

        ObjectMapper mapper = new ObjectMapper();
        settingService = new SettingService(settingRepo, mapper);

        MvDownloadTaskRepository taskRepo = (MvDownloadTaskRepository) Proxy.newProxyInstance(
                MvDownloadTaskRepository.class.getClassLoader(),
                new Class<?>[]{MvDownloadTaskRepository.class},
                (proxy, method, args) -> {
                    if ("save".equals(method.getName())) {
                        MvDownloadTask t = (MvDownloadTask) args[0];
                        t.setId(1001L);
                        savedTasks.add(t);
                        return t;
                    }
                    return null;
                });

        SongFileRepository songFileRepo = (SongFileRepository) Proxy.newProxyInstance(
                SongFileRepository.class.getClassLoader(),
                new Class<?>[]{SongFileRepository.class},
                (proxy, method, args) -> null);

        AppProperties props = new AppProperties();

        MvSearchProvider mockProvider = (MvSearchProvider) Proxy.newProxyInstance(
                MvSearchProvider.class.getClassLoader(),
                new Class<?>[]{MvSearchProvider.class},
                (proxy, method, args) -> {
                    if ("provider".equals(method.getName())) return MvProvider.NETEASE;
                    return null;
                });

        mvDownloadService = new MvDownloadService(
                List.of(mockProvider),
                taskRepo,
                songFileRepo,
                props,
                null,
                null,
                null,
                null,
                null,
                settingService,
                "ffmpeg"
        );
    }

   @Test
   void testDefaultSettingsValues() {
        setUp();
       // 默认基础配置：下载后自动加入队列=true，单音轨转双轨=false
        if (!settingService.isMvAutoEnqueue()) {
            throw new AssertionError("默认 isMvAutoEnqueue 应为 true");
        }
        if (settingService.isMvAutoConvertDualTrack()) {
            throw new AssertionError("默认 isMvAutoConvertDualTrack 应为 false");
        }

        Map<String, Object> all = settingService.getAll();
        if (!Boolean.TRUE.equals(all.get(SettingService.MV_AUTO_ENQUEUE))) {
            throw new AssertionError("MV_AUTO_ENQUEUE 配置应为 true");
        }
        if (!Boolean.FALSE.equals(all.get(SettingService.MV_AUTO_CONVERT_DUAL_TRACK))) {
            throw new AssertionError("MV_AUTO_CONVERT_DUAL_TRACK 配置应为 false");
        }

        // 测试保存更新后的基础配置
        settingService.putAll(Map.of(
                SettingService.MV_AUTO_ENQUEUE, false,
                SettingService.MV_AUTO_CONVERT_DUAL_TRACK, true
        ));
        if (settingService.isMvAutoEnqueue()) {
            throw new AssertionError("更新后 isMvAutoEnqueue 应为 false");
        }
        if (!settingService.isMvAutoConvertDualTrack()) {
            throw new AssertionError("更新后 isMvAutoConvertDualTrack 应为 true");
        }
    }

   @Test
   void testValidateSettingsRejectsNonBoolean() {
        setUp();
       // 基础配置值必须是布尔类型
        boolean thrown1 = false;
        try {
            settingService.putAll(Map.of(SettingService.MV_AUTO_ENQUEUE, "true"));
        } catch (ApiException e) {
            thrown1 = e.getMessage().contains("必须是布尔值");
        }
        if (!thrown1) {
            throw new AssertionError("非布尔值字符串未被正确拦截");
        }

        boolean thrown2 = false;
        try {
            settingService.putAll(Map.of(SettingService.MV_AUTO_CONVERT_DUAL_TRACK, 123));
        } catch (ApiException e) {
            thrown2 = e.getMessage().contains("必须是布尔值");
        }
        if (!thrown2) {
            throw new AssertionError("非布尔值整数未被正确拦截");
        }
    }

    @Test
    void testThirdPartySettingsLikeBilibiliAuthAreExcludedAndIgnored() {
        setUp();
        // 模拟数据库中存在 bilibili.auth 凭据记录
        Setting biliSetting = new Setting();
        biliSetting.setKey("bilibili.auth");
        biliSetting.setValue("{\"cookie\":\"SESSDATA=xyz\",\"uname\":\"test\"}");
        settingsDb.put("bilibili.auth", biliSetting);

        // 1. getAll() 应自动过滤掉 bilibili.auth，避免泄露给管理端设置页面
        Map<String, Object> all = settingService.getAll();
        if (all.containsKey("bilibili.auth")) {
            throw new AssertionError("getAll() 不得包含第三方认证凭据 bilibili.auth");
        }

        // 2. putAll() 即使接收到包含 bilibili.auth 的 map，也应安全忽略，绝不中断正常保存
        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put(SettingService.MV_AUTO_ENQUEUE, true);
        updatePayload.put(SettingService.MV_AUTO_CONVERT_DUAL_TRACK, true);
        updatePayload.put("bilibili.auth", "{\"cookie\":\"SESSDATA=xyz\"}");

        settingService.putAll(updatePayload);

        // 验证系统配置已成功保存
        if (!settingService.isMvAutoConvertDualTrack()) {
            throw new AssertionError("单音轨转双轨配置应成功更新为 true");
        }
    }

    @Test
   void testSubmitDownloadInheritsGlobalBasicSettingsWhenOmitted() {
        setUp();
       // 当调用方未传递 autoEnqueue 与 autoConvertDualTrack 时，自动继承基础配置
        MvDownloadSubmitRequest request = new MvDownloadSubmitRequest(
                "NETEASE",
                "mv123456",
                "七里香",
                "周杰伦",
                "https://example.com/cover.jpg",
                "1080p",
                null,
                null
        );

        MvDownloadTaskDto dto = mvDownloadService.submitDownload(request);
        if (dto == null) {
            throw new AssertionError("返回的任务 DTO 不能为空");
        }

        MvDownloadTask lastSaved = savedTasks.get(savedTasks.size() - 1);
        // 全局基础配置默认 autoConvertDualTrack 为 false
        if (lastSaved.isAutoConvertDualTrack()) {
            throw new AssertionError("缺省配置应继承基础配置 autoConvertDualTrack=false");
        }
    }

   @Test
   void testSubmitDownloadRespectsExplicitOverrides() {
        setUp();
       // 当调用方显式传递配置时，使用显式覆盖值
        MvDownloadSubmitRequest request = new MvDownloadSubmitRequest(
                "NETEASE",
                "mv789101",
                "稻香",
                "周杰伦",
                "https://example.com/cover2.jpg",
                "1080p",
                false,
                true
        );

        MvDownloadTaskDto dto = mvDownloadService.submitDownload(request);
        if (dto == null) {
            throw new AssertionError("返回的任务 DTO 不能为空");
        }

        MvDownloadTask lastSaved = savedTasks.get(savedTasks.size() - 1);
        // 显式传入 true，应覆盖全局配置为 true
        if (!lastSaved.isAutoConvertDualTrack()) {
            throw new AssertionError("显式参数应覆盖为 autoConvertDualTrack=true");
        }
    }

    @Test
    void testAiConfigAllowsBlankModelWhenDisabled() {
        setUp();
        AppProperties properties = new AppProperties();
        AppSecretRepository secretRepo = (AppSecretRepository) Proxy.newProxyInstance(
                AppSecretRepository.class.getClassLoader(),
                new Class<?>[]{AppSecretRepository.class},
                (proxy, method, args) -> {
                    if ("existsById".equals(method.getName())) return false;
                    if ("findById".equals(method.getName())) return Optional.empty();
                    return null;
                });

        SecretCryptoService crypto = new SecretCryptoService(properties);
        SettingRepository settingRepo = (SettingRepository) Proxy.newProxyInstance(
                SettingRepository.class.getClassLoader(),
                new Class<?>[]{SettingRepository.class},
                (proxy, method, args) -> {
                    if ("existsById".equals(method.getName())) return settingsDb.containsKey(args[0]);
                    if ("findById".equals(method.getName())) return Optional.ofNullable(settingsDb.get(args[0]));
                    if ("save".equals(method.getName())) {
                        Setting s = (Setting) args[0];
                        settingsDb.put(s.getKey(), s);
                        return s;
                    }
                    return null;
                });

        AiConfigService aiConfigService = new AiConfigService(properties, settingRepo, secretRepo, crypto, new ObjectMapper());

        // 未启用 AI 时，bulkModel 留空时不应抛出异常
        AiConfigService.ConfigUpdate disabledUpdate = new AiConfigService.ConfigUpdate(
                false, "", null, false, "", "", 60, 0.97, 0.92, "AUTO", 2, 1);
        aiConfigService.update(disabledUpdate);

        // 启用 AI 时，bulkModel 为空必须被拦截
        boolean thrown = false;
        try {
            AiConfigService.ConfigUpdate enabledEmptyModel = new AiConfigService.ConfigUpdate(
                    true, "https://api.openai.com/v1", null, false, "", "", 60, 0.97, 0.92, "AUTO", 2, 1);
            aiConfigService.update(enabledEmptyModel);
        } catch (ApiException e) {
            thrown = e.getMessage().contains("批量模型 ID 不能为空");
        }
        if (!thrown) {
            throw new AssertionError("启用 AI 时 bulkModel 为空必须抛出校验异常");
        }
    }
}
