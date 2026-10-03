# -*- coding: utf-8 -*-
"""清理测试数据:按来源 IP 删除教学条目。

用法: python cleanup_test_rows.py [ip ...]
不带参数时默认清理冒烟测试用过的 IP。
"""
import sqlite3
import sys

DEFAULT_IPS = ["10.4.4.4", "10.5.5.5", "10.7.7.7", "10.9.9.9"]


def main():
    ips = sys.argv[1:] or DEFAULT_IPS
    c = sqlite3.connect("webmarisa.db")
    total = 0
    for ip in ips:
        n = c.execute("DELETE FROM memorise WHERE ip = ?", (ip,)).rowcount
        if n:
            print("  %-12s 删除 %d 行" % (ip, n))
        total += n
    c.commit()
    print("合计删除 %d 行;剩余 %d 条" % (total, c.execute("SELECT COUNT(*) FROM memorise").fetchone()[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
