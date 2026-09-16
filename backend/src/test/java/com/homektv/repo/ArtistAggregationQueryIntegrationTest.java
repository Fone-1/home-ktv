package com.homektv.repo;

import com.homektv.domain.Song;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 PostgreSQL 执行管理后台歌手库聚合查询（阶段三修复的回归验证）。
 *
 * <p>背景：该查询曾因关联子查询引用外层未分组列被 PostgreSQL 拒绝
 * （{@code subquery uses ungrouped column "s.artist" from outer query}），本机无 PostgreSQL 时
 * 只能做结构约束测试；本测试在真实库上验证执行结果，包括众数类型、复核判定、三种筛选与分页/计数。
 */
@SpringBootTest
@Testcontainers
class ArtistAggregationQueryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv")
                    .withUsername("ktv")
                    .withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private SongRepository songRepository;

    private static Song song(String title, String artist, String gender, String[] locks) {
        Song song = new Song();
        song.setTitle(title);
        song.setArtist(artist);
        song.setArtistInit(artist.substring(0, 1));
        song.setArtistGender(gender);
        song.setMetadataLocks(locks);
        return song;
    }

    private Map<String, Object[]> rows(String keyword, String gender, String reviewed) {
        Page<Object[]> page = songRepository.pageAdminArtists(keyword, gender, reviewed, PageRequest.of(0, 50));
        return page.getContent().stream().collect(Collectors.toMap(
                row -> (String) row[0], row -> row, (a, b) -> a));
    }

    @Test
    void aggregatesArtistsWithModeGenderAndReviewedFlag() {
        songRepository.saveAll(List.of(
                // 周杰伦：女 2 + 男 1 → 众数 女；无锁 → 未复核
                song("晴天", "周杰伦", "女", new String[0]),
                song("七里香", "周杰伦", "女", new String[0]),
                song("稻香", "周杰伦", "男", new String[0]),
                // 邓丽君：全部锁 + 有效类型 → 已复核；空白与前后空格归一到同一歌手
                song("甜蜜蜜", "邓丽君", "女", new String[]{"artistGender"}),
                song("月亮代表我的心", " 邓丽君 ", "女", new String[]{"artistGender"}),
                // 空白歌手名归入「未知歌手」，无有效类型 → 众数回退「未知」
                song("伴奏一", "  ", "未知", new String[0]),
                song("伴奏二", "", "男", new String[0]),
                // 非 ok 状态不参与聚合
                song("待删", "林俊杰", "男", new String[0])
        ));
        Song pending = song("待删", "林俊杰", "男", new String[0]);
        pending.setStatus("pending");
        songRepository.save(pending);

        Page<Object[]> page = songRepository.pageAdminArtists("", "", "", PageRequest.of(0, 50));

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(3);
        Map<String, Object[]> byName = page.getContent().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> row));
        // 排序：歌曲数降序、同名内名字升序
        assertThat(page.getContent().stream().map(row -> (String) row[0]))
                .containsExactly("周杰伦", "邓丽君", "未知歌手");

        Object[] zhou = byName.get("周杰伦");
        assertThat(zhou[1]).isEqualTo("周");
        assertThat(zhou[2]).isEqualTo(3L);
        assertThat(zhou[3]).isEqualTo("女");
        assertThat(zhou[4]).isEqualTo(false);

        Object[] deng = byName.get("邓丽君");
        assertThat(deng[2]).isEqualTo(2L);
        assertThat(deng[4]).isEqualTo(true);

        Object[] unknown = byName.get("未知歌手");
        assertThat(unknown[2]).isEqualTo(2L);
        assertThat(unknown[3]).isEqualTo("未知");
        assertThat(unknown[4]).isEqualTo(false);
    }

    @Test
    void filtersByKeywordGenderAndReviewedOnAggregatedValues() {
        songRepository.saveAll(List.of(
                song("晴天", "周杰伦", "女", new String[]{"artistGender"}),
                song("红豆", "王菲", "女", new String[]{"artistGender"}),
                song("朋友", "周华健", "男", new String[0]),
                song("一生所爱", "周深", "男", new String[]{"artistGender"})
        ));

        assertThat(rows("华健", "", "").keySet()).containsExactly("周华健");
        assertThat(rows("", "女", "").keySet()).containsExactlyInAnyOrder("周杰伦", "王菲");
        assertThat(rows("周", "", "true").keySet()).containsExactlyInAnyOrder("周杰伦", "周深");
        assertThat(rows("", "", "false").keySet()).containsExactly("周华健");

        // countQuery 与主查询口径一致：筛选后总数正确
        assertThat(songRepository.pageAdminArtists("周", "", "true", PageRequest.of(0, 1)).getTotalElements())
                .isEqualTo(2);
        // 超出范围的页返回空内容但计数仍然正确
        Page<Object[]> emptyPage = songRepository.pageAdminArtists("", "", "", PageRequest.of(5, 50));
        assertThat(emptyPage.getContent()).isEmpty();
        assertThat(emptyPage.getTotalElements()).isEqualTo(4);
    }
}
