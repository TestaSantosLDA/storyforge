"""Tests for import_gutenberg.py: python -m unittest discover sources/tools"""
import tempfile
import unittest
from pathlib import Path

import import_gutenberg as ig


def stanza(tag):
    return [f"{tag} verso {i}," for i in range(1, 9)]


def book(counts, numbered=True, drop_number=None):
    out = ["The Project Gutenberg eBook of Os Lusíadas", "*** START OF THE PROJECT GUTENBERG EBOOK ***", ""]
    for c, n in enumerate(counts, start=1):
        out += [f"Canto {ig.ORDINALS[c - 1].capitalize()}", ""]
        for s in range(1, n + 1):
            if numbered and s != drop_number:
                out.append(str(s))
            out += stanza(f"c{c}s{s}") + [""]
    out += ["*** END OF THE PROJECT GUTENBERG EBOOK ***", "licence text 1", ""]
    return "\n".join(out)


class ImportTest(unittest.TestCase):

    def test_parses_cantos_and_stanzas(self):
        cantos = ig.parse(book([3, 2]))
        self.assertEqual(sorted(cantos), [1, 2])
        self.assertEqual(len(cantos[1]), 3)
        self.assertEqual(cantos[2][1][0], "c2s2 verso 1,")
        ig.check(cantos, [3, 2])

    def test_a_missing_number_line_is_filled_by_position(self):
        cantos = ig.parse(book([4], drop_number=3))
        self.assertEqual(cantos[1][2][0], "c1s3 verso 1,")
        ig.check(cantos, [4])

    def test_refuses_wrong_counts(self):
        with self.assertRaisesRegex(ValueError, "canto 2 has 2 stanzas, expected 3"):
            ig.check(ig.parse(book([3, 2])), [3, 3])

    def test_refuses_a_short_stanza(self):
        text = book([2]).replace("c1s2 verso 8,\n", "")
        with self.assertRaisesRegex(ValueError, "stanza 2 has 7 lines"):
            ig.check(ig.parse(text), [2])

    def test_refuses_out_of_order_numbers(self):
        with self.assertRaisesRegex(ValueError, "numbered 3 is in position 2"):
            ig.parse(book([3]).replace("\n2\n", "\n3\n", 1))

    def test_writes_the_format_the_backend_reads(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            (out / "source.yaml").write_text("id: x\nstanza_counts: [2, 1]\n", encoding="utf-8")
            src = out / "pg.txt"
            src.write_text(book([2, 1]), encoding="utf-8")
            ig.main(["--file", str(src), "--out", str(out)])
            text = (out / "canto-01.txt").read_text(encoding="utf-8")
            self.assertTrue(text.startswith("1\nc1s1 verso 1,\n"))
            self.assertIn("\n\n2\nc1s2 verso 1,", text)
            self.assertTrue((out / "canto-02.txt").exists())


if __name__ == "__main__":
    unittest.main()
