#!/usr/bin/env python3
"""
生成调用接口所需的三个验签请求头。

用法：
    # 密钥从环境变量读（推荐，别写进命令行历史）
    export PRECHECK_AUTH_SECRET=dev-secret
    python tools/sign.py 张三

    # 或者显式传
    python tools/sign.py 张三 --secret dev-secret

    # 想验证"签名过期"的报错，可以把时间戳往前拨
    python tools/sign.py 张三 --offset -600

输出会直接给出一段可以复制运行的 curl，连同样例接口一起。
这是「零依赖」的：只用 Python 标准库，不装任何东西。
"""
import argparse
import hashlib
import hmac
import os
import sys
import time
from urllib.parse import quote

HEADER_APPLICANT = "X-Precheck-Applicant"
HEADER_TIMESTAMP = "X-Precheck-Timestamp"
HEADER_SIGNATURE = "X-Precheck-Signature"


def make_signature(secret: str, ts_ms: int, applicant: str) -> str:
    """与 Java 侧完全一致：HMAC-SHA256(secret, 时间戳 + 换行 + 原始姓名) 的小写十六进制。

    注意这里用的是**原始姓名**，不是 percent-encoding 之后的串。
    请求头里传编码值、签名算原值——这样同一个字无论编成 %E5 还是 %e5，签名都一致。
    """
    payload = f"{ts_ms}\n{applicant}".encode("utf-8")
    return hmac.new(secret.encode("utf-8"), payload, hashlib.sha256).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="生成预检接口的验签请求头")
    parser.add_argument("applicant", help="申请人姓名，例如 张三")
    parser.add_argument("--secret", default=os.environ.get("PRECHECK_AUTH_SECRET", ""),
                        help="共享密钥，默认读环境变量 PRECHECK_AUTH_SECRET")
    parser.add_argument("--offset", type=int, default=0,
                        help="时间戳偏移秒数，负数表示“过去”，用来验证过期拦截")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090",
                        help="服务地址，默认本机 8090")
    args = parser.parse_args()

    if not args.secret:
        print("缺少密钥：请设置环境变量 PRECHECK_AUTH_SECRET，或用 --secret 传入", file=sys.stderr)
        return 2

    applicant = args.applicant.strip()
    if not applicant:
        print("申请人不能为空", file=sys.stderr)
        return 2

    ts = int(time.time() * 1000) + args.offset * 1000
    sig = make_signature(args.secret, ts, applicant)
    # 请求头只能放 ASCII，中文必须编码后再传；签名算的是编码前的原名
    header_name = quote(applicant, safe="")

    print(f"{HEADER_APPLICANT}: {header_name}")
    print(f"{HEADER_TIMESTAMP}: {ts}")
    print(f"{HEADER_SIGNATURE}: {sig}")
    print()
    print("可以直接复制运行（验证样例接口）：")
    print(f"""curl -s "{args.base_url}/api/precheck/sample" \\
  -H '{HEADER_APPLICANT}: {header_name}' \\
  -H '{HEADER_TIMESTAMP}: {ts}' \\
  -H '{HEADER_SIGNATURE}: {sig}'""")
    return 0


if __name__ == "__main__":
    sys.exit(main())
