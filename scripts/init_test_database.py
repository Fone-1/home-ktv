# -*- coding: utf-8 -*-
"""
====================================================================
家庭 KTV 项目 - 测试数据库初始化脚本
====================================================================
功能描述：
1. 自动连接 192.168.1.88:5433 上的 PostgreSQL 数据库。
2. 确保目标测试数据库 ktv_test 已创建。
3. 读取 backend/src/main/resources/db/migration 目录下的所有版本迁移 SQL 文件（V1 ~ V16）。
4. 严格按照版本号顺序执行 SQL 脚本，在测试数据库中创建完整的数据表、索引与约束。
5. 同步写入 flyway_schema_history 迁移记录，确保 Spring Boot 启动时能无缝识别并兼容 Flyway 机制。
"""

import os
import re
import time
import psycopg2
from psycopg2.extensions import ISOLATION_LEVEL_AUTOCOMMIT

# 数据库连接参数
DB_CONFIG = {
    "host": "192.168.1.88",
    "port": 5433,
    "user": "ktv",
    "password": "12345678.",
    "dbname": "ktv_test",
    "admin_db": "postgres"
}

# 迁移脚本所在目录（backend 下的 resources 目录）
MIGRATION_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "backend", "src", "main", "resources", "db", "migration"
)

# 与正式库 Flyway 校验和完全对齐的元数据（用于保证 Spring Boot 启动时不报错）
FLYWAY_METADATA = {
    1: {"desc": "init schema", "checksum": -1113494092},
    2: {"desc": "song file valid", "checksum": 1149345771},
    3: {"desc": "vocal track confidence", "checksum": -362035605},
    4: {"desc": "ai library", "checksum": 958398673},
    5: {"desc": "favorites", "checksum": 154650778},
    6: {"desc": "split library and import records", "checksum": 568118142},
    7: {"desc": "source library fields", "checksum": 1501063386},
    8: {"desc": "idempotent write constraints", "checksum": -784481498},
    9: {"desc": "open ai metadata repair", "checksum": 39589453},
    10: {"desc": "admin library query indexes", "checksum": -692672703},
    11: {"desc": "artist gender", "checksum": -40402195},
    12: {"desc": "music metadata sources", "checksum": -1139319926},
    13: {"desc": "music metadata scrape tasks", "checksum": 299211003},
    14: {"desc": "metadata scrape skip existing", "checksum": 1640584281},
    15: {"desc": "preserve source history", "checksum": 1021241507},
    16: {"desc": "mv download tasks", "checksum": -1151817533},
}


def ensure_database_exists():
    """
    检查并确保目标测试数据库存在，不存在则自动创建
    """
    print(f"[1/4] 检查目标测试库 '{DB_CONFIG['dbname']}' 是否存在...")
    conn = psycopg2.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        dbname=DB_CONFIG["admin_db"],
        connect_timeout=5
    )
    conn.set_isolation_level(ISOLATION_LEVEL_AUTOCOMMIT)
    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s;", (DB_CONFIG["dbname"],))
        if not cur.fetchone():
            print(f"      正在创建数据库 {DB_CONFIG['dbname']}...")
            cur.execute(f"CREATE DATABASE {DB_CONFIG['dbname']} WITH OWNER = {DB_CONFIG['user']} ENCODING = 'UTF8';")
            print("      创建完成！")
        else:
            print("      数据库已存在。")
    conn.close()


def ensure_flyway_table(cur):
    """
    初始化 Flyway 迁移历史表结构
    """
    cur.execute("""
        CREATE TABLE IF NOT EXISTS flyway_schema_history (
            installed_rank integer NOT NULL,
            version character varying(50),
            description character varying(200) NOT NULL,
            type character varying(20) NOT NULL,
            script character varying(1000) NOT NULL,
            checksum integer,
            installed_by character varying(100) NOT NULL,
            installed_on timestamp without time zone DEFAULT now() NOT NULL,
            execution_time integer NOT NULL,
            success boolean NOT NULL,
            CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
        );
        CREATE INDEX IF NOT EXISTS flyway_schema_history_s_idx ON flyway_schema_history (success);
    """)


def execute_migrations():
    """
    读取并按版本升序执行所有 SQL 迁移文件
    """
    print(f"[2/4] 从 {MIGRATION_DIR} 加载 SQL 文件...")
    if not os.path.exists(MIGRATION_DIR):
        raise FileNotFoundError(f"未找到迁移脚本目录: {MIGRATION_DIR}")

    # 获取所有符合 V<version>__<description>.sql 命名的脚本
    sql_files = []
    for fname in os.listdir(MIGRATION_DIR):
        match = re.match(r"^V(\d+)__(.+)\.sql$", fname)
        if match:
            version_num = int(match.group(1))
            sql_files.append((version_num, fname, os.path.join(MIGRATION_DIR, fname)))

    # 按版本号升序排列
    sql_files.sort(key=lambda x: x[0])
    print(f"      找到 {len(sql_files)} 个迁移脚本 (V{sql_files[0][0]} ~ V{sql_files[-1][0]})")

    # 连接到目标测试库进行表结构初始化
    print(f"[3/4] 开始向测试库 '{DB_CONFIG['dbname']}' 导入表结构...")
    conn = psycopg2.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        dbname=DB_CONFIG["dbname"],
        connect_timeout=10
    )

    with conn.cursor() as cur:
        # 确保 flyway 表存在
        ensure_flyway_table(cur)

        # 查询当前已经执行过的迁移版本
        cur.execute("SELECT version FROM flyway_schema_history WHERE success = true;")
        installed_versions = set(row[0] for row in cur.fetchall())

        for rank, fname, fpath in sql_files:
            ver_str = str(rank)
            if ver_str in installed_versions:
                print(f"      [跳过] V{ver_str} ({fname}) 已经执行过。")
                continue

            print(f"      [执行中] V{ver_str} : {fname} ...")
            with open(fpath, "r", encoding="utf-8") as f:
                sql_content = f.read()

            start_t = time.time()
            # 执行迁移 SQL 语句
            cur.execute(sql_content)
            elapsed_ms = int((time.time() - start_t) * 1000)

            # 获取 Flyway 描述和校验和
            meta = FLYWAY_METADATA.get(rank, {"desc": fname, "checksum": None})

            # 插入 Flyway 迁移历史记录
            cur.execute("""
                INSERT INTO flyway_schema_history (
                    installed_rank, version, description, type, script, checksum, installed_by, execution_time, success
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                rank,
                ver_str,
                meta["desc"],
                "SQL",
                fname,
                meta["checksum"],
                DB_CONFIG["user"],
                max(elapsed_ms, 1),
                True
            ))
            # 提交单个脚本的修改
            conn.commit()
            print(f"      [成功] V{ver_str} 执行完毕 (耗时: {elapsed_ms}ms)")

    conn.close()


def verify_tables():
    """
    验证测试库中的所有已创建表
    """
    print(f"[4/4] 验证测试库 '{DB_CONFIG['dbname']}' 中的表结构...")
    conn = psycopg2.connect(
        host=DB_CONFIG["host"],
        port=DB_CONFIG["port"],
        user=DB_CONFIG["user"],
        password=DB_CONFIG["password"],
        dbname=DB_CONFIG["dbname"],
        connect_timeout=5
    )

    with conn.cursor() as cur:
        # 查询所有用户数据表
        cur.execute("""
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
            ORDER BY table_name;
        """)
        tables = [row[0] for row in cur.fetchall()]
        print(f"      共创建 {len(tables)} 张表:")
        for t in tables:
            print(f"       - {t}")

        # 检查 Flyway 历史记录数
        cur.execute("SELECT count(*) FROM flyway_schema_history WHERE success = true;")
        count = cur.fetchone()[0]
        print(f"      Flyway 迁移版本数: {count} 条")

    conn.close()


if __name__ == "__main__":
    try:
        ensure_database_exists()
        execute_migrations()
        verify_tables()
        print("\n====================================================================")
        print(">>> 测试库初始化全部成功！与 Spring Boot 迁移机制完全兼容。")
        print("====================================================================")
    except Exception as e:
        print(f"\n[错误] 初始化失败: {e}")
