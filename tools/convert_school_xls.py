"""Convert a merged-cell school XLS locally; never execute macros or upload data."""
import argparse
import csv
import datetime as dt
import io
import json
from pathlib import Path
import re
import sys
import uuid

HEADERS = ["课程名称", "教师", "教室", "星期", "开始节次", "结束节次", "周次", "颜色", "备注"]
COLORS = ["#6960CE", "#397E75", "#B46A39", "#A44D75", "#4F76B5", "#82703F"]


def need(condition, message):
    if not condition:
        raise ValueError(message)


def parse_weeks(value, count):
    value = value.replace("周", "").replace("，", ",").replace("（", "(").replace("）", ")").replace(" ", "")
    weeks = set()
    for part in value.split(","):
        match = re.fullmatch(r"(\d{1,2})(?:-(\d{1,2}))?(?:\((单|双)\))?", part)
        need(match is not None, f"无法识别周次规则：{part}")
        first = int(match[1])
        last = int(match[2] or match[1])
        need(1 <= first <= last <= count, f"周次不在 1–{count} 范围：{part}")
        weeks.update(n for n in range(first, last + 1) if not match[3] or n % 2 == (1 if match[3] == "单" else 0))
    need(bool(weeks), "周次规则未选中任何周")
    return sorted(weeks)


def safe_cell(value):
    value = str(value)
    if value.startswith(("'", "\t", "\n", "\r")) or value.lstrip().startswith(("=", "+", "-", "@")):
        return "'" + value
    return value


def convert(source, count):
    import xlrd
    need(source.stat().st_size <= 4 * 1024 * 1024, "源文件超过 4 MiB")
    book = xlrd.open_workbook(str(source), formatting_info=True, on_demand=True)
    try:
        sheet = book.sheet_by_index(0)
        need(sheet.nrows <= 200 and sheet.ncols <= 30, "表格尺寸超出该转换器支持范围")
        header = None
        day_columns = {}
        time_column = None
        for r in range(min(20, sheet.nrows)):
            values = [str(sheet.cell_value(r, c)).strip() for c in range(sheet.ncols)]
            if "星期一" in values or "周一" in values:
                header = r
                for c, value in enumerate(values):
                    for day, suffix in enumerate("一二三四五六日", 1):
                        if value in ("星期" + suffix, "周" + suffix):
                            day_columns[c] = day
                time_column = next((c for c, value in enumerate(values) if value == "时间"), None)
                break
        need(header is not None and len(day_columns) == 7 and time_column is not None, "未找到七天/时间表头；该文件不是已适配的网格课表")
        first_day_column = min(day_columns)
        period_rows = {}
        periods = []
        for r in range(header + 1, sheet.nrows):
            number = next((int(sheet.cell_value(r, c)) for c in range(first_day_column)
                           if isinstance(sheet.cell_value(r, c), (int, float))
                           and sheet.cell_value(r, c) == len(periods) + 1), None)
            if number is None:
                continue
            times = re.findall(r"\d{1,2}:\d{2}", str(sheet.cell_value(r, time_column)))
            need(len(times) == 2, f"第 {r + 1} 行缺少明确的起止时间")
            start, end = [dt.datetime.strptime(v, "%H:%M").strftime("%H:%M") for v in times]
            need(start < end and (not periods or start >= periods[-1]["end"]), f"第 {r + 1} 行作息顺序错误")
            period_rows[r] = number
            periods.append({"number": number, "start": start, "end": end})
        need(1 <= len(periods) <= 48, "未找到有效连续节次")
        semester_id = str(uuid.uuid4())
        courses = {}
        meetings = []
        seen = set()
        for row, start_period in period_rows.items():
            for column, weekday in day_columns.items():
                text = str(sheet.cell_value(row, column)).strip()
                if not text:
                    continue
                merged = next((m for m in sheet.merged_cells if m[0] <= row < m[1] and m[2] <= column < m[3]), None)
                if merged:
                    need(merged[0] == row and merged[2] == column and merged[3] == column + 1,
                         f"第 {row + 1} 行有跨星期的合并内容，需要人工处理")
                    end_period = max((n for r, n in period_rows.items() if merged[0] <= r < merged[1]), default=start_period)
                else:
                    end_period = start_period
                for block in re.split(r"(?:…{2,}|\.{6,})", text):
                    lines = [v.strip() for v in block.splitlines() if v.strip()]
                    if not lines:
                        continue
                    info_index = next((i for i, line in enumerate(lines) if "【" in line and "】" in line), None)
                    need(info_index is not None and info_index > 0, f"第 {row + 1} 行、第 {column + 1} 列无法识别教师和周次")
                    match = re.fullmatch(r"(.*?)【([^】]+)】", lines[info_index])
                    need(match is not None, f"第 {row + 1} 行周次格式不明确")
                    name = " ".join(lines[:info_index])
                    teacher = match[1].strip()
                    location = " ".join(lines[info_index + 1:])
                    need(len(name) <= 160 and len(teacher) <= 160 and len(location) <= 200, "课程字段过长")
                    weeks = parse_weeks(match[2], count)
                    key = name, teacher
                    if key not in courses:
                        courses[key] = {"id": str(uuid.uuid4()), "semesterId": semester_id, "name": name,
                                        "teacher": teacher, "color": COLORS[len(courses) % len(COLORS)], "note": ""}
                    course = courses[key]
                    signature = course["id"], weekday, start_period, end_period, tuple(weeks), location
                    if signature in seen:
                        continue
                    seen.add(signature)
                    meetings.append({"id": str(uuid.uuid4()), "courseId": course["id"], "weekday": weekday,
                                     "startPeriod": start_period, "endPeriod": end_period, "weeks": weeks, "location": location})
        need(bool(meetings), "没有解析出任何课程，未生成输出")
        school = next((str(sheet.cell_value(r, c)).strip() for r in range(header) for c in range(sheet.ncols)
                       if re.fullmatch(r"[^\n：:]{2,50}(大学|学院)", str(sheet.cell_value(r, c)).strip())), "")
        return semester_id, school, periods, list(courses.values()), meetings
    finally:
        book.release_resources()


def main(argv=None):
    parser = argparse.ArgumentParser(description="本地转换已适配的合并单元格 XLS 学生课表；不保存学生姓名/学号/班级。")
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path, help="新建 .csv 或 .coursatrix 文件；已有文件不会覆盖")
    parser.add_argument("--weeks", type=int, default=20)
    parser.add_argument("--first-monday", help="生成 .coursatrix 时必填，学校第一教学周的周一 YYYY-MM-DD")
    parser.add_argument("--name", default="导入的学期")
    args = parser.parse_args(argv)
    source, output = args.input.resolve(), args.output.resolve()
    need(source.is_file(), "输入文件不存在")
    need(output.parent.is_dir(), "输出父目录不存在")
    need(not output.exists(), "输出文件已存在。请使用新文件名，避免覆盖原数据")
    need(1 <= args.weeks <= 60, "总周数应在 1–60 之间")
    need(output.suffix.lower() in (".csv", ".coursatrix"), "输出扩展名必须为 .csv 或 .coursatrix")
    monday = None
    if output.suffix.lower() == ".coursatrix":
        need(bool(args.first_monday), "生成专用课表必须用 --first-monday 明确指定第一周周一，不能根据上课周次猜测")
        monday = dt.date.fromisoformat(args.first_monday)
        need(monday.weekday() == 0, "--first-monday 必须是周一")
    semester_id, school, periods, courses, meetings = convert(source, args.weeks)
    if output.suffix.lower() == ".csv":
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="\r\n")
        writer.writerow(HEADERS)
        by_id = {c["id"]: c for c in courses}
        for meeting in meetings:
            course = by_id[meeting["courseId"]]
            writer.writerow([safe_cell(v) for v in [course["name"], course["teacher"], meeting["location"],
                meeting["weekday"], meeting["startPeriod"], meeting["endPeriod"],
                ",".join(map(str, meeting["weeks"])), course["color"], ""]])
        content = chr(0xFEFF) + buffer.getvalue()
    else:
        content = json.dumps({"format": "coursatrix", "schemaVersion": 1, "kind": "TIMETABLE",
            "exportedAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"),
            "data": {"semester": {"id": semester_id, "school": school, "name": args.name,
                "firstMonday": monday.isoformat(), "weekCount": args.weeks}, "periods": periods,
                "courses": courses, "meetings": meetings, "tasks": [],
                "preferences": {"theme": "SYSTEM", "showWeekend": True}}}, ensure_ascii=False, indent=2)
    need(len(content.encode("utf-8")) <= 4 * 1024 * 1024, "转换结果超过 4 MiB，未写入")
    with output.open("x", encoding="utf-8", newline="") as stream:
        stream.write(content)
    print(f"已本地转换：{len(courses)} 门课程，{len(meetings)} 条安排，{len(periods)} 节作息。")
    print("未保存学生姓名、学号或班级；课程中的教师/教室仍会保留。")
    print("请校对每门课的周次、单双周、连堂节次及地点。CSV 不包含作息时间，导入前需配置作息。")
    print("识别到的作息：" + "；".join(f'{p["number"]}: {p["start"]}–{p["end"]}' for p in periods))
    return 0


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    try:
        raise SystemExit(main())
    except (ValueError, OSError, ImportError) as exc:
        print(f"转换失败：{exc}", file=sys.stderr)
        raise SystemExit(1)
