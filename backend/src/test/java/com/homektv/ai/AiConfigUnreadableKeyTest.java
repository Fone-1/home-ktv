package com.homektv.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.AppProperties;
import com.homektv.domain.AppSecret;
import com.homektv.repo.AppSecretRepository;
import com.homektv.repo.SettingRepository;
import com.homektv.web.ApiException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 配置主密钥变化后的降级行为（回归测试）。
 *
 * <p>背景：线上出现 {@code AEADBadTagException: Tag mismatch} —— 已保存的 AI API Key 是用旧主密钥
 * 加密的，主密钥来源变化后无法解密，异常一路冒到 {@code GET /api/admin/ai/config} 变成 500，
 * 连带依赖该接口的后台页面（如歌手库的 AI 复核）一起报错。
 *
 * <p>期望行为：这是可预期的运维状态，应当降级为「AI 未配置」并在响应里明确标注，让管理员能重新保存密钥，
 * 而不是让整个接口失败。
 */
class AiConfigUnreadableKeyTest {

    @TempDir Path temp;

    @Test
    void realKeyRotationMakesStoredSecretUnreadable() throws Exception {
        assumeFileBasedMasterKey();
        // 用密钥文件 A 加密，再用密钥文件 B 解密：必须抛出「不可读」而不是底层 AEAD 异常
        Path keyA = temp.resolve("a.key");
        Path keyB = temp.resolve("b.key");
        SecretCryptoService encryptor = new SecretCryptoService(propertiesWithKeyPath(keyA));
        SecretCryptoService decryptor = new SecretCryptoService(propertiesWithKeyPath(keyB));

        SecretCryptoService.EncryptedValue value = encryptor.encrypt("ai.api_key", "sk-secret-value");

        assertThatThrownBy(() -> decryptor.decrypt("ai.api_key", value.ciphertext(), value.nonce()))
                .isInstanceOf(SecretCryptoService.SecretUnreadableException.class)
                .hasMessageContaining("重新保存");
        assertThat(decryptor.describeKeySource()).contains("b.key");
    }

    @Test
    void sameKeyStillDecryptsNormally() throws Exception {
        assumeFileBasedMasterKey();
        Path shared = temp.resolve("shared.key");
        SecretCryptoService first = new SecretCryptoService(propertiesWithKeyPath(shared));
        SecretCryptoService second = new SecretCryptoService(propertiesWithKeyPath(shared));

        SecretCryptoService.EncryptedValue value = first.encrypt("ai.api_key", "sk-still-readable");

        assertThat(second.decrypt("ai.api_key", value.ciphertext(), value.nonce())).isEqualTo("sk-still-readable");
        // 密钥文件由首次使用时的自动生成落到磁盘，后续实例直接复用
        assertThat(Files.exists(shared)).isTrue();
    }

    @Test
    void configResponseDegradesInsteadOfFailing() {
        AiConfigService service = serviceWithUnreadableStoredKey();

        // 不再抛异常，而是报告「密钥存在但不可读」
        AiConfigService.ConfigResponse response = service.response();
        assertThat(response.apiKeyConfigured()).isFalse();
        assertThat(response.apiKeyUnreadable()).isTrue();
        assertThat(response.sources()).containsEntry("api_key", "UNREADABLE");
    }

    @Test
    void isConfiguredIsFalseWhenStoredKeyIsUnreadable() {
        AiConfigService service = serviceWithUnreadableStoredKey();

        AiConfigService.ResolvedConfig resolved = service.resolve();
        // 关键：即使库里把 enabled 打开着，也不能声称已配置
        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.apiKeyUnreadable()).isTrue();
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    void requireConfiguredGivesActionableMessage() {
        AiConfigService service = serviceWithUnreadableStoredKey();

        assertThatThrownBy(service::requireConfigured)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无法解密")
                .hasMessageContaining("重新填写并保存");
    }

    private AppProperties propertiesWithKeyPath(Path keyPath) {
        AppProperties props = new AppProperties();
        props.setConfigMasterKeyPath(keyPath.toString());
        props.setDataPath(keyPath.getParent().toString());
        return props;
    }

    /** 环境变量主密钥会覆盖密钥文件来源，轮换场景只在文件路径来源下验证。 */
    private void assumeFileBasedMasterKey() {
        String environment = System.getenv("KTV_CONFIG_MASTER_KEY");
        Assumptions.assumeTrue(environment == null || environment.isBlank(),
                "设置了 KTV_CONFIG_MASTER_KEY 时密钥文件不生效，跳过文件轮换测试");
    }

    /** 构造一个「数据库里有密钥但用当前主密钥解不开」的 AiConfigService。 */
    private AiConfigService serviceWithUnreadableStoredKey() {
        AppProperties props = new AppProperties();
        props.setDataPath(temp.toString());
        props.getAi().setEnabled(true);
        props.getAi().setBaseUrl("http://ai.test/v1");
        props.getAi().setBulkModel("test-model");

        SettingRepository settings = mock(SettingRepository.class);
        when(settings.findById(anyString())).thenReturn(Optional.empty());
        when(settings.existsById(anyString())).thenReturn(false);

        AppSecret stored = new AppSecret();
        stored.setKey("ai.api_key");
        stored.setCiphertext(new byte[]{1, 2, 3, 4});
        stored.setNonce(new byte[12]);
        AppSecretRepository secrets = mock(AppSecretRepository.class);
        when(secrets.findById("ai.api_key")).thenReturn(Optional.of(stored));
        when(secrets.existsById("ai.api_key")).thenReturn(true);

        SecretCryptoService crypto = mock(SecretCryptoService.class);
        when(crypto.decrypt(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new SecretCryptoService.SecretUnreadableException("密文与当前主密钥不匹配", null));

        return new AiConfigService(props, settings, secrets, crypto, new ObjectMapper());
    }
}
