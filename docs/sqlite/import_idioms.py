#!/usr/bin/env python3
"""
九家输入法 - 成语导入工具
==========================
将成语文本文件导入到 phrases 表，自动计算 digit_seq 和 freq。

输入格式:
  每行一个成语（纯中文，如"安居乐业"），支持 UTF-8 / GBK / GB2312 编码

处理流程:
  1. 逐字查 chars 表获取拼音（取最高频）
  2. 拼音转九宫格数字序列（abc→2, def→3, ...）
  3. 按成语长度设置初始频率（4字词 freq=600, 更长更高）
  4. 去重检查（已存在则跳过）

用法:
  python3 import_idioms.py <成语.txt> <数据库.db>

示例:
  python3 import_idioms.py idioms.txt app/src/main/assets/pinyin_dict.db
"""

import sqlite3
import sys
import os
import re

# 字母 → 九宫格数字键映射（与 PinyinEngine.LETTER_TO_DIGIT 一致）
LETTER_TO_DIGIT = {
    'a': 2, 'b': 2, 'c': 2,
    'd': 3, 'e': 3, 'f': 3,
    'g': 4, 'h': 4, 'i': 4,
    'j': 5, 'k': 5, 'l': 5,
    'm': 6, 'n': 6, 'o': 6,
    'p': 7, 'q': 7, 'r': 7, 's': 7,
    't': 8, 'u': 8, 'v': 8,
    'w': 9, 'x': 9, 'y': 9, 'z': 9,
}


def read_text_file(path):
    """读取文本文件，自动检测编码（UTF-8 → GBK → GB2312）"""
    with open(path, 'rb') as f:
        raw = f.read()

    for enc in ['utf-8', 'gbk', 'gb2312', 'gb18030', 'latin-1']:
        try:
            text = raw.decode(enc)
            # 验证是否包含中文
            if re.search(r'[\u4e00-\u9fff]', text):
                return [line.strip() for line in text.splitlines() if line.strip()]
        except (UnicodeDecodeError, UnicodeError):
            continue

    # 最后尝试 utf-8 with errors='replace'
    text = raw.decode('utf-8', errors='replace')
    return [line.strip() for line in text.splitlines() if line.strip()]


def pinyin_to_digit_seq(pinyin):
    """将拼音字符串转为数字序列，如 'anju' -> '2658'"""
    return ''.join(str(LETTER_TO_DIGIT[c]) for c in pinyin.lower() if c in LETTER_TO_DIGIT)


def idiom_to_digit_seq(db, idiom):
    """将成语转为数字序列，逐字查 chars 表获取拼音"""
    digits = []
    for ch in idiom:
        # 非汉字跳过
        if not ('\u4e00' <= ch <= '\u9fff'):
            return None, f"非汉字: '{ch}' (U+{ord(ch):04X})"

        # 查 chars 表，取最高频拼音
        row = db.execute(
            "SELECT pinyin FROM chars WHERE hanzi=? ORDER BY freq DESC LIMIT 1",
            (ch,)
        ).fetchone()

        if not row:
            return None, f"未找到拼音: '{ch}' (U+{ord(ch):04X})"

        py = row[0]
        digits.append(pinyin_to_digit_seq(py))

    return ''.join(digits), None


def import_idioms(txt_path, db_path):
    """导入成语到数据库"""
    if not os.path.exists(txt_path):
        print(f"错误: 文件不存在: {txt_path}")
        sys.exit(1)
    if not os.path.exists(db_path):
        print(f"错误: 数据库不存在: {db_path}")
        sys.exit(1)

    # 读取成语
    idioms = read_text_file(txt_path)
    if not idioms:
        print("错误: 文件为空或无有效内容")
        sys.exit(1)

    print(f"读取 {len(idioms)} 条成语")

    db = sqlite3.connect(db_path)

    # 确保 source 列存在
    cols = [c[1] for c in db.execute("PRAGMA table_info(phrases)").fetchall()]
    if 'source' not in cols:
        db.execute("ALTER TABLE phrases ADD COLUMN source TEXT NOT NULL DEFAULT 'system'")
        print("  [+] 添加 source 列")

    # 确保 ngram_adjacency 表存在（成语邻接联想）
    db.execute("""
        CREATE TABLE IF NOT EXISTS ngram_adjacency (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            context TEXT NOT NULL,
            next_word TEXT NOT NULL,
            freq INTEGER NOT NULL DEFAULT 1,
            updated_at INTEGER NOT NULL,
            UNIQUE(context, next_word)
        )
    """)
    db.execute("CREATE INDEX IF NOT EXISTS idx_ngram_context ON ngram_adjacency(context)")

    now = int(__import__('time').time())

    added = 0
    skipped_dup = 0
    skipped_error = 0
    adjacency_added = 0
    errors = []

    db.execute("BEGIN")

    for idiom in idioms:
        # 跳过空行和纯数字/英文
        if not re.search(r'[\u4e00-\u9fff]', idiom):
            skipped_error += 1
            continue

        # 计算数字序列
        digit_seq, error = idiom_to_digit_seq(db, idiom)
        if error:
            skipped_error += 1
            errors.append(f"  {idiom}: {error}")
            continue

        # 按长度设置初始频率
        freq = len(idiom) * 150  # 4字=600, 5字=750, 6字=900, ...

        # 检查是否已存在
        existing = db.execute(
            "SELECT 1 FROM phrases WHERE digit_seq=? AND phrase=?",
            (digit_seq, idiom)
        ).fetchone()
        if existing:
            skipped_dup += 1
        else:
            # 插入词组
            db.execute(
                "INSERT INTO phrases (digit_seq, phrase, freq, source) VALUES (?, ?, ?, 'system')",
                (digit_seq, idiom, freq)
            )
            added += 1

        # === 建立邻接联想：前缀 → 后缀 ===
        # 例如 "哀莫大于心死"→ (哀→莫大于心死), (哀莫→大于心死), (哀莫大→于心死), (哀莫大于→心死)
        # 最少需要有2个前缀字 + 1个后缀字，即至少3字成语
        idiom_len = len(idiom)
        for prefix_len in range(1, idiom_len - 1):  # 前缀1~N-2字，后缀至少2字
            if prefix_len > 4:  # 前缀最多4字（与 queryAdjacency 4级回退一致）
                break
            context = idiom[:prefix_len]
            suffix = idiom[prefix_len:]
            # 用 INSERT OR REPLACE 避免重复
            db.execute(
                "INSERT OR REPLACE INTO ngram_adjacency (context, next_word, freq, updated_at) "
                "VALUES (?, ?, ?, ?)",
                (context, suffix, freq, now)
            )
            adjacency_added += 1

    db.execute("COMMIT")

    # 统计
    total = db.execute("SELECT COUNT(*) FROM phrases").fetchone()[0]
    sys_cnt = db.execute(
        "SELECT COUNT(*) FROM phrases WHERE source='system'"
    ).fetchone()[0]
    adj_total = db.execute("SELECT COUNT(*) FROM ngram_adjacency").fetchone()[0]

    print(f"\n{'='*45}")
    print(f"  导入完成")
    print(f"{'='*45}")
    print(f"  新增成语:     {added}")
    print(f"  跳过(重复):   {skipped_dup}")
    print(f"  跳过(异常):   {skipped_error}")
    print(f"  phrases 总数: {total} (system: {sys_cnt})")
    print(f"  邻接联想条数: {adjacency_added} (总计: {adj_total})")

    if errors:
        print(f"\n  异常详情 (前10条):")
        for e in errors[:10]:
            print(e)

    db.close()


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    import_idioms(sys.argv[1], sys.argv[2])