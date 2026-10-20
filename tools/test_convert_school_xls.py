import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("school_converter", Path(__file__).with_name("convert_school_xls.py"))
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)


class FakeSheet:
    nrows, ncols = 5, 11
    merged_cells = [(3, 5, 4, 5)]

    def __init__(self):
        self.cells = [[""] * self.ncols for _ in range(self.nrows)]
        self.cells[0][2] = "示例大学"
        self.cells[1][2] = "姓名：不应保存  学号：不应保存"
        self.cells[2][3] = "时间"
        for i, day in enumerate("一二三四五六日", 4):
            self.cells[2][i] = "星期" + day
        self.cells[3][2], self.cells[4][2] = 1.0, 2.0
        self.cells[3][3], self.cells[4][3] = "8:00\n8:45", "8:55\n9:40"
        self.cells[3][4] = "示例课程\n\n示例教师【1-6(单)周】\nA101\n………………\n示例实验\n示例教师【2-6(双)周】\nB202"

    def cell_value(self, row, col):
        return self.cells[row][col]


class FakeBook:
    def __init__(self, sheet):
        self.sheet = sheet
        self.released = False

    def sheet_by_index(self, _):
        return self.sheet

    def release_resources(self):
        self.released = True


class ConverterTest(unittest.TestCase):
    def test_parenthesized_parity_and_explicit_weeks(self):
        self.assertEqual([1, 3, 5], converter.parse_weeks("1-6(单)周", 20))
        self.assertEqual([2, 4, 6], converter.parse_weeks("2-6（双）周", 20))
        self.assertEqual([5, 6, 7, 8, 10, 12], converter.parse_weeks("5-8,10-10,12-12周", 20))

    def test_invalid_week_rules_are_not_guessed(self):
        for rule in ("0-6", "6-2", "1-99", "不明", "2-2(单)"):
            with self.assertRaises(ValueError):
                converter.parse_weeks(rule, 20)

    def test_merged_spans_multiple_courses_and_no_student_identity(self):
        with tempfile.TemporaryDirectory(prefix="coursatrix-test-") as directory:
            source = Path(directory) / "synthetic.xls"
            source.write_bytes(b"synthetic")
            book = FakeBook(FakeSheet())
            with patch("xlrd.open_workbook", return_value=book):
                _, school, periods, courses, meetings = converter.convert(source, 20)
            self.assertTrue(book.released)
            self.assertEqual("示例大学", school)
            self.assertEqual(2, len(periods))
            self.assertEqual(2, len(courses))
            self.assertEqual(2, len(meetings))
            self.assertTrue(all(m["startPeriod"] == 1 and m["endPeriod"] == 2 for m in meetings))
            self.assertEqual([[1, 3, 5], [2, 4, 6]], [m["weeks"] for m in meetings])
            self.assertNotIn("不应保存", str((courses, meetings, periods, school)))

    def test_unrecognized_cells_fail_and_close_workbook(self):
        with tempfile.TemporaryDirectory(prefix="coursatrix-test-") as directory:
            source = Path(directory) / "synthetic.xls"
            source.write_bytes(b"synthetic")
            sheet = FakeSheet()
            sheet.cells[3][4] = "没有周次的未知内容"
            book = FakeBook(sheet)
            with patch("xlrd.open_workbook", return_value=book), self.assertRaises(ValueError):
                converter.convert(source, 20)
            self.assertTrue(book.released)

    def test_formula_protection_and_literal_apostrophe(self):
        for value in ("=1+1", "-x", "+x", "@x", "'literal", "\t=1"):
            self.assertEqual("'" + value, converter.safe_cell(value))
        self.assertEqual("课程", converter.safe_cell("课程"))

    def test_existing_output_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix="coursatrix-test-") as directory:
            source, output = Path(directory) / "source.xls", Path(directory) / "existing.csv"
            source.write_bytes(b"synthetic")
            output.write_text("keep", encoding="utf-8")
            with self.assertRaises(ValueError):
                converter.main([str(source), str(output)])
            self.assertEqual("keep", output.read_text(encoding="utf-8"))

    def test_native_export_requires_explicit_first_monday(self):
        with tempfile.TemporaryDirectory(prefix="coursatrix-test-") as directory:
            source = Path(directory) / "source.xls"
            source.write_bytes(b"synthetic")
            with self.assertRaises(ValueError):
                converter.main([str(source), str(Path(directory) / "output.coursatrix")])


if __name__ == "__main__":
    unittest.main()
