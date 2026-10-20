"""Isolated Redis tests for the read-only legacy exporter; no business data is used."""
import argparse
from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

from export_blog_likes import RedisReader, export


class ExportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="hmdp-like-export-")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        executable = os.environ.get("HMDP_TEST_REDIS_EXECUTABLE", "redis-server")
        cls.log = open(Path(cls.directory.name) / "redis.log", "wb")
        cls.process = subprocess.Popen(
            [executable, "--bind", "127.0.0.1", "--port", str(cls.port),
             "--save", "", "--appendonly", "no", "--dir", cls.directory.name],
            cwd=cls.directory.name, stdout=cls.log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 10
        while True:
            try:
                cls.redis = RedisReader("127.0.0.1", cls.port)
                cls.redis.command("PING")
                break
            except OSError:
                if time.monotonic() > deadline or cls.process.poll() is not None:
                    cls.process.terminate()
                    cls.process.wait(timeout=10)
                    cls.log.close()
                    raise RuntimeError("Isolated Redis failed: " + str(Path(cls.directory.name) / "redis.log"))
                time.sleep(0.05)

    @classmethod
    def tearDownClass(cls):
        cls.redis.close()
        cls.process.terminate()
        cls.process.wait(timeout=10)
        cls.log.close()
        cls.directory.cleanup()

    def setUp(self):
        self.redis.command("FLUSHDB")  # private server started above, random port
        self.output_dir = tempfile.TemporaryDirectory(prefix="hmdp-like-sql-")
        self.addCleanup(self.output_dir.cleanup)
        self.args = argparse.Namespace(host="127.0.0.1", port=self.port, db=0,
                                       output=str(Path(self.output_dir.name) / "likes.sql"), writes_paused=True)

    def run_export(self):
        with patch.dict(os.environ, {"HMDP_REDIS_PASSWORD": ""}), redirect_stdout(io.StringIO()):
            export(self.args)

    def test_exports_relationships_and_timestamps_without_modifying_redis(self):
        self.redis.command("ZADD", "blog:liked:100", 1700000000000, 8, 1700000000001, 9)
        self.run_export()
        sql = Path(self.args.output).read_text(encoding="utf-8")
        self.assertIn("(8,100,1700000000000)", sql)
        self.assertIn("(9,100,1700000000001)", sql)
        self.assertIn("START TRANSACTION;", sql)
        self.assertNotIn("UPDATE tb_blog SET", sql)
        self.assertEqual(2, self.redis.command("ZCARD", "blog:liked:100"))
        self.assertEqual(b"1700000000000", self.redis.command("ZSCORE", "blog:liked:100", 8))

    def test_requires_paused_writes_acknowledgement(self):
        self.args.writes_paused = False
        with self.assertRaises(ValueError):
            self.run_export()
        self.assertFalse(Path(self.args.output).exists())

    def test_wrong_key_type_aborts_before_creating_output(self):
        self.redis.command("SET", "blog:liked:100", "wrong-type")
        with self.assertRaises(ValueError):
            self.run_export()
        self.assertFalse(Path(self.args.output).exists())

    def test_invalid_legacy_user_id_aborts_export(self):
        self.redis.command("ZADD", "blog:liked:100", 1, "invalid-user")
        with self.assertRaises(Exception):
            self.run_export()
        self.assertFalse(Path(self.args.output).exists())

    def test_existing_output_is_never_overwritten(self):
        Path(self.args.output).write_text("previous export", encoding="utf-8")
        with self.assertRaises(FileExistsError):
            self.run_export()
        self.assertEqual("previous export", Path(self.args.output).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
