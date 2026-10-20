"""Read-only Redis exporter. Generates SQL for review; never connects to MySQL.

Stop legacy like writes while exporting/importing. Requires Python 3, no packages.
Pass a Redis password through HMDP_REDIS_PASSWORD, never a command-line argument.
"""
import argparse
from decimal import Decimal
import os
from pathlib import Path
import socket


class RedisReader:
    def __init__(self, host, port):
        self.connection = socket.create_connection((host, port), timeout=10)
        self.reader = self.connection.makefile("rb")

    def command(self, *args):
        fields = [str(arg).encode("utf-8") for arg in args]
        payload = b"*%d\r\n" % len(fields)
        payload += b"".join(b"$%d\r\n" % len(field) + field + b"\r\n" for field in fields)
        self.connection.sendall(payload)
        return self.read_reply()

    def read_reply(self):
        line = self.reader.readline()
        if not line.endswith(b"\r\n"):
            raise RuntimeError("Incomplete Redis reply")
        kind, value = line[:1], line[1:-2]
        if kind == b"-":
            # Do not echo server errors, which may include authentication details.
            raise RuntimeError("Redis rejected the export command")
        if kind == b"+":
            return value
        if kind == b":":
            return int(value)
        if kind == b"*":
            return [self.read_reply() for _ in range(int(value))]
        if kind == b"$":
            length = int(value)
            if length == -1:
                return None
            data = self.reader.read(length)
            if len(data) != length or self.reader.read(2) != b"\r\n":
                raise RuntimeError("Incomplete Redis bulk reply")
            return data
        raise RuntimeError("Unsupported Redis reply")

    def close(self):
        self.reader.close()
        self.connection.close()


def integer(value, allow_zero=False):
    number = Decimal(value.decode("ascii") if isinstance(value, bytes) else value)
    if not number.is_finite() or number != number.to_integral_value():
        raise ValueError("Legacy IDs/timestamps must be finite integers")
    result = int(number)
    if result < (0 if allow_zero else 1) or result > 9223372036854775807:
        raise ValueError("Legacy ID/timestamp is out of Java long range")
    return result


def export(args):
    if not args.writes_paused:
        raise ValueError("Pause legacy like writes and acknowledge with --writes-paused")
    client = RedisReader(args.host, args.port)
    relationships = {}
    try:
        password = os.environ.get("HMDP_REDIS_PASSWORD")
        if password:
            client.command("AUTH", password)
        client.command("SELECT", args.db)
        cursor = b"0"
        while True:
            cursor, keys = client.command("SCAN", cursor.decode(), "MATCH", "blog:liked:*", "COUNT", 200)
            for key in keys:
                if client.command("TYPE", key.decode()) != b"zset":
                    raise ValueError("A legacy like key is not a ZSet; inspect it before migration")
                blog = integer(key.split(b":")[-1])
                members_cursor = b"0"
                while True:
                    members_cursor, entries = client.command("ZSCAN", key.decode(), members_cursor.decode(), "COUNT", 500)
                    for index in range(0, len(entries), 2):
                        user = integer(entries[index])
                        created = integer(entries[index + 1], allow_zero=True)
                        pair = (user, blog)
                        relationships[pair] = min(created, relationships.get(pair, created))
                    if members_cursor == b"0":
                        break
            if cursor == b"0":
                break
    finally:
        client.close()
    # Exclusive creation prevents accidentally overwriting a previous migration snapshot.
    with Path(args.output).open("x", encoding="utf-8", newline="\n") as output:
        output.write("-- Review before running. Writes must remain paused. Counters are NOT reset here.\nSTART TRANSACTION;\n")
        for (user, blog), created in sorted(relationships.items()):
            output.write("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES"
                         f"({user},{blog},{created}) ON DUPLICATE KEY UPDATE "
                         "created_at=LEAST(created_at,VALUES(created_at));\n")
        output.write("COMMIT;\n-- Run the discrepancy/orphan queries in db/blog_like.sql before rollout.\n")
    print(f"Exported {len(relationships)} relationships to {args.output}. Redis was read only; MySQL was not accessed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6379)
    parser.add_argument("--db", type=int, required=True, help="Redis database used by the legacy app")
    parser.add_argument("--output", required=True, help="New SQL export file (must not exist)")
    parser.add_argument("--writes-paused", action="store_true")
    export(parser.parse_args())
