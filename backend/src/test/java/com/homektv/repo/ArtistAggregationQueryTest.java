package com.homektv.repo;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 歌手库聚合查询的结构约束（回归测试）。
 *
 * <p>背景：该查询按「归一化歌手名」分组，早期版本在 SELECT/HAVING 里用关联子查询取主类型，
 * 子查询引用了外层未分组的 {@code s.artist}，PostgreSQL 直接报
 * {@code subquery uses ungrouped column "s.artist" from outer query}，导致歌手库页面 500。
 *
 * <p>因此本查询采用「只用聚合函数、不含任何子查询」的实现：主类型用 {@code MODE() WITHIN GROUP}，
 * 复核状态用 {@code BOOL_AND}，筛选条件放在外层对聚合结果过滤。
 * 下面把这个约束固化下来，避免以后有人改回关联子查询又踩同一个坑。
 */
class ArtistAggregationQueryTest {

    @Test
    void artistAggregationDoesNotUseSubqueries() throws Exception {
        Query query = queryAnnotation();

        assertThat(query.value().toUpperCase(Locale.ROOT))
                .as("分组查询中不得出现子查询（分组列在子查询里会触发 PostgreSQL ungrouped column 错误）")
                .doesNotContain("(SELECT");
        assertThat(query.countQuery().toUpperCase(Locale.ROOT))
                .as("countQuery 同样不得出现子查询")
                .doesNotContain("(SELECT");
    }

    @Test
    void artistAggregationUsesAggregatesForGenderAndReviewed() throws Exception {
        Query query = queryAnnotation();
        String sql = query.value().toUpperCase(Locale.ROOT);

        // 主类型必须用取众数的有序集聚合，复核状态用布尔聚合
        assertThat(sql).contains("MODE() WITHIN GROUP");
        assertThat(sql).contains("BOOL_AND");
        // 归一化歌手名必须作为唯一分组维度
        assertThat(sql).contains("GROUP BY N.ARTIST_NAME");
    }

    @Test
    void artistAggregationFiltersAreAppliedToAggregatedValues() throws Exception {
        Query query = queryAnnotation();
        String sql = query.value();

        // 关键词/类型/复核状态都在外层对聚合结果过滤，因此不需要 HAVING
        assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("HAVING");
        assertThat(sql).contains(":gender = ''");
        assertThat(sql).contains(":reviewed = ''");
        assertThat(sql).contains(":keyword = ''");
    }

    @Test
    void reviewedFilterUsesStringParameterToAvoidNullableBooleanBinding() throws Exception {
        Method method = SongRepository.class.getMethod("pageAdminArtists",
                String.class, String.class, String.class, Pageable.class);

        // 可空 Boolean 在原生查询里容易出现参数类型推断问题，统一用 ""/true/false 字符串
        assertThat(method.getParameterTypes()[2]).isEqualTo(String.class);
        assertThat(queryAnnotation().value()).contains("g.reviewed = (:reviewed = 'true')");
    }

    private Query queryAnnotation() throws Exception {
        Method method = SongRepository.class.getMethod("pageAdminArtists",
                String.class, String.class, String.class, Pageable.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query).as("pageAdminArtists 必须声明 @Query").isNotNull();
        assertThat(query.nativeQuery()).isTrue();
        return query;
    }
}
