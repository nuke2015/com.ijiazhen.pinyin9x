#!/usr/bin/env python3
"""
九家输入法 - 数据库脱敏清理工具
=================================
清除数据库中的用户个人数据，保留系统基础词典，用于开源发布。

清除内容:
  - phrases 表中 source='user' 的用户词组
  - hot_sentences 热句联想表（全部）
  - ngram_adjacency 邻接词表（全部）
  - clipboard 剪切记录表（全部）
  - favorites 收藏记录表（全部）
  - 重置 sqlite_sequence 自增计数器

保留内容:
  - chars 单字表（全部）
  - phrases 表中 source='system' 的系统词组

用法:
  python3 clean_user_data.py <输入.db> [输出.db]

示例:
  python3 clean_user_data.py pinyin_dict.db                    # 原地清理
  python3 clean_user_data.py feng.db clean_pinyin_dict.db      # 输出到新文件
"""

import sqlite3
import shutil
import sys
import os
from datetime import datetime


def clean_database(db_path, output_path):
    """清理数据库中的用户数据"""
    # 如果输入输出不同，先复制
    if os.path.abspath(db_path) != os.path.abspath(output_path):
        shutil.copy2(db_path, output_path)
        print(f"已复制 {db_path} -> {output_path}")

    db = sqlite3.connect(output_path)

    stats_before = {}
    stats_after = {}

    try:
        # ---- 统计清理前数据 ----
        for table in ["phrases", "hot_sentences", "ngram_adjacency", "clipboard", "favorites"]:
            try:
                cnt = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                stats_before[table] = cnt
            except:
                pass

        # 统计 user 词组数
        try:
            user_cnt = db.execute(
                "SELECT COUNT(*) FROM phrases WHERE source='user'"
            ).fetchone()[0]
            stats_before["phrases_user"] = user_cnt
        except:
            stats_before["phrases_user"] = 0

        # ---- 执行清理 ----
        db.execute("BEGIN")

        # 1. 删除用户词组（保留系统词）
        deleted = db.execute(
            "DELETE FROM phrases WHERE source='user'"
        ).rowcount
        print(f"  phrases: 删除 {deleted} 条用户词组")

        # 2. 清空热句表
        if table_exists(db, "hot_sentences"):
            deleted = db.execute("DELETE FROM hot_sentences").rowcount
            print(f"  hot_sentences: 删除 {deleted} 条")

        # 3. 清空邻接词表
        if table_exists(db, "ngram_adjacency"):
            deleted = db.execute("DELETE FROM ngram_adjacency").rowcount
            print(f"  ngram_adjacency: 删除 {deleted} 条")

        # 4. 清空剪切板
        if table_exists(db, "clipboard"):
            deleted = db.execute("DELETE FROM clipboard").rowcount
            print(f"  clipboard: 删除 {deleted} 条")

        # 5. 清空收藏夹
        if table_exists(db, "favorites"):
            deleted = db.execute("DELETE FROM favorites").rowcount
            print(f"  favorites: 删除 {deleted} 条")

        # 6. 重置自增序列
        db.execute("DELETE FROM sqlite_sequence")

        db.execute("COMMIT")

        # 7. 压缩数据库（VACUUM 回收空间）
        db.execute("VACUUM")

        # ---- 统计清理后数据 ----
        for table in ["phrases", "hot_sentences", "ngram_adjacency", "clipboard", "favorites"]:
            try:
                cnt = db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                stats_after[table] = cnt
            except:
                stats_after[table] = 0

        try:
            sys_cnt = db.execute(
                "SELECT COUNT(*) FROM phrases WHERE source='system'"
            ).fetchone()[0]
            stats_after["phrases_system"] = sys_cnt
        except:
            stats_after["phrases_system"] = 0

        # ---- 打印报告 ----
        print(f"\n{'='*45}")
        print(f"  清理完成")
        print(f"{'='*45}")
        print(f"  {'表名':<20} {'清理前':>8} {'清理后':>8}")
        print(f"  {'-'*36}")

        for table in ["phrases", "hot_sentences", "ngram_adjacency",
                      "clipboard", "favorites"]:
            before = stats_before.get(table, 0)
            after = stats_after.get(table, 0)
            if before > 0 or after > 0:
                print(f"  {table:<20} {before:>8} {after:>8}")

        if "phrases_user" in stats_before:
            print(f"  {'  (其中 user 词)':<20} {stats_before['phrases_user']:>8} {0:>8}")
        if "phrases_system" in stats_after:
            print(f"  {'  (其中 system 词)':<20} {stats_before.get('phrases', 0) - stats_before.get('phrases_user', 0):>8} {stats_after['phrases_system']:>8}")

        chars_cnt = db.execute("SELECT COUNT(*) FROM chars").fetchone()[0]
        print(f"  {'chars (保留)':<20} {chars_cnt:>8} {chars_cnt:>8}")

        # 文件大小
        file_size = os.path.getsize(output_path)
        print(f"\n  输出文件: {output_path} ({format_size(file_size)})")

    except Exception as e:
        db.execute("ROLLBACK")
        print(f"\n清理失败: {e}")
        sys.exit(1)
    finally:
        db.close()


def table_exists(db, table_name):
    info = db.execute(
        f"SELECT name FROM sqlite_master WHERE type='table' AND name='{table_name}'"
    ).fetchone()
    return info is not None


def format_size(size):
    if size < 1024:
        return f"{size} B"
    elif size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    else:
        return f"{size / (1024 * 1024):.1f} MB"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    db_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else db_path

    if not os.path.exists(db_path):
        print(f"错误: 数据库不存在: {db_path}")
        sys.exit(1)

    print(f"输入: {db_path}")
    print(f"输出: {output_path}")
    print(f"时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
    print("开始清理用户数据...")

    clean_database(db_path, output_path)


if __name__ == "__main__":
    main()