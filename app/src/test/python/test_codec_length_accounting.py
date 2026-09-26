"""Reference-only checks for the optimized JSON-array length arithmetic.

These tests do not execute Kotlin or Android code; they validate the math used by
AgentConversationCodec against Python's compact JSON encoder and UTF-16 units.
"""
import json
import random
import unittest


def utf16_units(value):
    return len(value.encode("utf-16-le", "surrogatepass")) // 2


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class CodecLengthAccountingTest(unittest.TestCase):
    def test_suffix_and_notice_lengths_match_compact_json(self):
        rng = random.Random(20260926)
        alphabet = ["a", "中文", "😀", '"', "\\", "\n", "\r", "\t"]
        notice = {"role": "system", "content": "压缩"}
        notice_length = utf16_units(compact(notice))
        for _ in range(240):
            rows = [
                {
                    "role": rng.choice(("user", "assistant", "tool")),
                    "content": "".join(rng.choice(alphabet) for _ in range(rng.randrange(20))),
                }
                for _ in range(rng.randrange(33))
            ]
            encoded_lengths = [utf16_units(compact(row)) + 1 for row in rows]
            prefix = [0]
            for length in encoded_lengths:
                prefix.append(prefix[-1] + length)

            for start in range(len(rows) + 1):
                suffix = rows[start:]
                expected = utf16_units(compact(suffix))
                counted = 2 if not suffix else prefix[-1] - prefix[start] + 1
                self.assertEqual(expected, counted, (rows, start))
                if suffix:
                    with_notice = [notice] + suffix
                    self.assertEqual(
                        utf16_units(compact(with_notice)),
                        expected + notice_length + 1,
                        (rows, start),
                    )


if __name__ == "__main__":
    unittest.main()
