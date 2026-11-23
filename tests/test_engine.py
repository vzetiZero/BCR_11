# -*- coding: utf-8 -*-
"""Kiểm thử engine JSON của Electron (bcr/service/engine.py) + phương pháp 1-1.

Không cần Chrome: Chrome được thay bằng đối tượng giả. Mục tiêu là khoá lại hợp đồng
giữa UI Electron và tiến trình Python:

    - chỉ còn một trò Baccarat, dispatch đúng method
    - phương pháp 1-1: tay 1 Banker, tay 2 Player… xen kẽ, gấp thếp 8 bậc
    - chọn bàn chỉ xét số tay đã đi
    - cấu hình/thang cược được lưu ra file state, chịu được state cũ
"""

import json
import pathlib
import tempfile
import time
import unittest

from bcr.adapters import browser as BR
from bcr.models import engine as E
from bcr.models import method as ME
from bcr.service import engine


class FakeEmitter:
    def __init__(self):
        self.events = []

    def event(self, name, data):
        self.events.append((name, data))

    def reply(self, request_id, result=None, error=None):
        self.events.append(("reply", {"id": request_id, "result": result, "error": error}))

    def send(self, payload):
        self.events.append(("send", payload))


def fake_browser(connected=True, note=""):
    return type("FakeBrowser", (), {
        "connected": connected, "note": note, "mode": "cdp" if connected else "",
        "stop": lambda self: None,
    })()


def make_service(config=None, path=None):
    folder = pathlib.Path(tempfile.mkdtemp())
    service = engine.BaccaratService(path or (folder / "state.json"), FakeEmitter())
    service.browser = fake_browser()
    if config:
        reply = service.config_set(config)
        assert reply["ok"], reply
    return service


def table_row(service, letters, **extra):
    now = time.time()
    row = {"key": "id:t1", "label": "Bàn 1", "letters": letters, "ts": now,
           "table_id": "t1", "game_type": "", "read_error": "", "source": "evo-bead",
           "conflict": False, "stable": 3, "screen_stable": 3}
    row.update(extra)
    service.tables = {"id:t1": row}
    return row


class MethodTests(unittest.TestCase):
    """Luật 1-1 thuần: xen kẽ B/P, gấp thếp, hoà là hoàn tiền."""

    ROAD = "B" * ME.MIN_HANDS          # đủ số tay tối thiểu để được vào bàn

    def test_ladder_default_is_eight_doubling_rungs(self):
        self.assertEqual(ME.DEFAULT_LADDER, [20, 40, 80, 160, 320, 640, 1280, 2560])
        self.assertEqual(sum(ME.DEFAULT_LADDER), 5100)
        self.assertEqual(ME.ladder_stakes(None), [float(v) for v in ME.DEFAULT_LADDER])

    def test_multiply_builds_eight_rungs_from_the_first_step(self):
        self.assertEqual(ME.multiply_ladder(50), [50, 100, 200, 400, 800, 1600, 3200, 6400])

    def test_plan_starts_with_banker_at_the_first_rung(self):
        plan = ME.plan(list(self.ROAD), ME.DEFAULT_LADDER)
        self.assertEqual(plan["action"], "start")
        self.assertEqual(plan["side"], E.BANKER)
        self.assertEqual(plan["stake"], 20.0)

    def test_plan_waits_when_the_road_is_too_short(self):
        plan = ME.plan(["B"] * (ME.MIN_HANDS - 1), ME.DEFAULT_LADDER)
        self.assertEqual(plan["action"], "wait")
        self.assertAlmostEqual(plan["stake"], 0.0)

    def test_plan_halts_after_the_last_rung(self):
        plan = ME.plan(list(self.ROAD), ME.DEFAULT_LADDER, ladder_step=8, started=True)
        self.assertEqual(plan["action"], "halt")
        self.assertIn("8 bậc", plan["reason"])

    def test_win_resets_the_ladder_and_flips_the_side(self):
        state = {"started": True, "ladder_step": 3, "side": E.BANKER}
        new, settled = ME.advance(state, E.BANKER, E.BANKER, 160)
        self.assertEqual(settled["outcome"], "win")
        self.assertEqual(new["ladder_step"], 0)
        self.assertEqual(new["side"], E.PLAYER)

    def test_loss_goes_to_the_next_rung_and_flips_the_side(self):
        state = {"started": True, "ladder_step": 0, "side": E.BANKER}
        new, settled = ME.advance(state, E.PLAYER, E.BANKER, 20)
        self.assertEqual(settled["outcome"], "loss")
        self.assertEqual(new["ladder_step"], 1)
        self.assertEqual(new["side"], E.PLAYER)

    def test_tie_is_a_push_and_changes_nothing(self):
        state = {"started": True, "ladder_step": 2, "side": E.PLAYER}
        new, settled = ME.advance(state, E.TIE, E.PLAYER, 80)
        self.assertEqual(settled["outcome"], "push")
        self.assertAlmostEqual(settled["delta"], 0.0)
        self.assertEqual(new, state)

    def test_check_table_only_looks_at_the_hand_count(self):
        short = ME.check_table(list("B" * (ME.MIN_HANDS - 1)))
        self.assertFalse(short["eligible"])
        self.assertEqual(len(short["checks"]), 1)

        good = ME.check_table(list(self.ROAD))
        self.assertTrue(good["eligible"])
        self.assertNotIn("strong", good)

        long_road = ME.check_table(list("B" * (ME.MAX_HANDS + 1)))
        self.assertFalse(long_road["eligible"])

    def test_road_alternation_does_not_depend_on_the_actual_road(self):
        """Cầu thực tế (bệt hay xen kẽ) không đổi cửa của tay kế tiếp."""
        bet = {"started": True, "ladder_step": 0, "side": E.BANKER}
        for road in ("BBBBBB", "PPPPPP", "BPBPBP", "BPBPPB"):
            plan = ME.plan(list(road) + list(self.ROAD), ME.DEFAULT_LADDER, 0,
                           bet["side"], True)
            self.assertEqual(plan["side"], bet["side"], road)


class DispatchTests(unittest.TestCase):
    def test_ping_and_state_are_answered(self):
        service = make_service()
        result, error = engine.dispatch(service, "ping", {})
        self.assertIsNone(error)
        self.assertTrue(result["pong"])

        snapshot, error = engine.dispatch(service, "state", {})
        self.assertIsNone(error)
        self.assertEqual(snapshot["game"], "baccarat")
        self.assertEqual(snapshot["table"]["ladder"], [float(v) for v in ME.DEFAULT_LADDER])
        self.assertEqual(snapshot["table"]["method"], ME.METHOD_NAME)
        self.assertFalse(snapshot["tool_running"])

    def test_unknown_method_reports_an_error(self):
        service = make_service()
        result, error = engine.dispatch(service, "khong.co", {})
        self.assertIsNone(result)
        self.assertIn("không hỗ trợ", error)

    def test_build_service_only_knows_baccarat(self):
        emit = FakeEmitter()
        service = engine.build_service("baccarat", emit,
                                      pathlib.Path(tempfile.mkdtemp()) / "b.json")
        try:
            self.assertIsInstance(service, engine.BaccaratService)
        finally:
            service.shutdown()
        with self.assertRaises(SystemExit):
            engine.build_service("longlong", emit, pathlib.Path(tempfile.mkdtemp()) / "l.json")


class ConfigTests(unittest.TestCase):
    def test_defaults_expose_the_eight_rung_ladder(self):
        service = make_service()
        config = service.config
        self.assertEqual([float(v) for v in config["ladder"]],
                         [float(v) for v in ME.DEFAULT_LADDER])
        self.assertEqual(config["ladder_mode"], "manual")
        self.assertEqual((config["min_hands"], config["max_hands"]),
                         (ME.MIN_HANDS, ME.MAX_HANDS))
        self.assertNotIn("profile", config)
        self.assertNotIn("block_mode", config)

    def test_config_set_saves_a_manual_ladder(self):
        service = make_service()
        reply = service.config_set({"ladder": [20, 40, 80]})
        self.assertTrue(reply["ok"], reply)
        saved = json.loads(service.state_path.read_text(encoding="utf-8"))
        self.assertEqual(saved["settings"]["ladder"], [20, 40, 80])
        self.assertEqual(service.sim.ladder, [20.0, 40.0, 80.0])

    def test_multiply_mode_regenerates_the_ladder_from_the_first_rung(self):
        service = make_service({"ladder_mode": "multiply", "ladder_base": 50})
        self.assertEqual(service.config["ladder"], [50, 100, 200, 400, 800, 1600, 3200, 6400])

    def test_fixed_mode_uses_the_same_stake_for_every_hand(self):
        service = make_service({"ladder_mode": "fixed", "flat_stake": 30})
        self.assertEqual(service.config["ladder"], [30.0] * len(ME.DEFAULT_LADDER))
        self.assertEqual(service.sim.fixed_stake, 30.0)

    def test_config_set_rejects_a_bad_ladder(self):
        service = make_service()
        reply = service.config_set({"ladder": [0, 10]})
        self.assertFalse(reply["ok"])
        self.assertIn("lớn hơn 0", reply["error"])

    def test_hand_range_and_exit_streak_are_clamped(self):
        service = make_service()
        service.config_set({"min_hands": 1, "max_hands": 999, "live_exit_streak": 99})
        self.assertEqual(service.config["min_hands"], ME.HANDS_LOWER)
        self.assertEqual(service.config["max_hands"], ME.HANDS_UPPER)
        self.assertEqual(service.config["live_exit_streak"], ME.STREAK_UPPER)

    def test_session_new_keeps_the_ladder_settings(self):
        service = make_service({"ladder_mode": "multiply", "ladder_base": 40,
                                "min_hands": 12, "max_hands": 24})
        self.assertTrue(service.session_new({"capital": 4000})["ok"])
        self.assertEqual(service.sim.ladder, [40, 80, 160, 320, 640, 1280, 2560, 5120])
        self.assertEqual((service.sim.min_hands, service.sim.max_hands), (12, 24))
        self.assertAlmostEqual(service.sim.money.start_capital, 4000.0)

    def test_session_reset_returns_to_the_first_rung(self):
        service = make_service()
        with_outcome = service.sim.method_state
        with_outcome["started"] = True
        with_outcome["ladder_step"] = 4
        self.assertTrue(service.session_reset()["ok"])
        self.assertFalse(service.sim.method_state["started"])
        self.assertEqual(service.sim.method_state["ladder_step"], 0)
        self.assertEqual(service.sim.results, [])

    def test_state_file_with_old_profile_keys_is_tolerated(self):
        folder = pathlib.Path(tempfile.mkdtemp())
        path = folder / "state.json"
        path.write_text(json.dumps({
            "format": "live-v2-engine",
            "settings": {"profile": "3-3", "block_mode": "repeat", "max_streak": 3,
                         "min_lead": 3, "max_lead": 5, "tie_side": "B",
                         "ladder": [10, 20, 40, 80, 160, 320], "start_url": "ftp://x"},
        }), encoding="utf-8")
        service = engine.BaccaratService(path, FakeEmitter())
        self.assertNotIn("profile", service.config)
        self.assertTrue(service.config["start_url"].startswith("http"))
        self.assertEqual(service.config["ladder"], [10.0, 20.0, 40.0, 80.0, 160.0, 320.0])


class TableRowTests(unittest.TestCase):
    def service(self, letters, config=None):
        service = make_service(config)
        service.browser = fake_browser()
        table_row(service, letters)
        return service

    def test_a_table_with_enough_hands_is_eligible(self):
        row = self.service("B" * 20).table_rows()[0]
        self.assertTrue(row["eligible"], row["verdict"])
        self.assertEqual(row["hands"], 20)
        self.assertNotIn("strong", row)
        self.assertNotIn("lead", row)
        self.assertNotIn("tie_side", row)
        self.assertEqual(len(row["checks"]), 1)

    def test_a_table_outside_the_hand_range_is_rejected(self):
        short = self.service("B" * (ME.MIN_HANDS - 1)).table_rows()[0]
        self.assertFalse(short["eligible"])
        self.assertIn("ván này đã đi", short["verdict"])

    def test_the_hand_range_can_be_widened(self):
        service = self.service("B" * 40, {"min_hands": 10, "max_hands": 20})
        row = service.table_rows()[0]
        self.assertFalse(row["eligible"], "40 tay vượt max 20")
        service.config_set({"min_hands": 30, "max_hands": 50})
        self.assertTrue(service.table_rows()[0]["eligible"])

    def test_heavy_streaks_do_not_disqualify_a_table(self):
        """Phương pháp 1-1 không xét bệt, nên bệt 8 tay vẫn đạt điều kiện vào bàn."""
        row = self.service("B" * 8 + "P" * 8 + "B" * 8).table_rows()[0]
        self.assertTrue(row["eligible"], row["verdict"])

    def test_no_tables_are_listed_without_chrome(self):
        service = self.service("B" * 20)
        service.browser = fake_browser(connected=False, note="Chrome đã đóng")
        self.assertEqual(service.snapshot()["tables"], [])


class EnterValidTests(unittest.TestCase):
    """Nút duy nhất “VÀO BÀN HỢP LỆ”: tìm + mở + xác nhận nhưng KHÔNG cược."""

    VALID = "B" * 22

    def service(self):
        service = make_service({"min_hands": 10, "max_hands": 50})
        service.browser = fake_browser()
        table_row(service, self.VALID)
        return service

    def prime(self, service, letters=None):
        service.enter_valid = True
        service.valid_check_done = False
        service.auto_start_key = "id:t1"
        service.auto_start_letters = (service.tables["id:t1"]["letters"]
                                      if letters is None else letters)
        service.auto_start_deadline = time.time() + 60

    def test_enter_valid_binds_the_table_without_starting_a_session(self):
        service = self.service()
        self.prime(service)
        service._auto_start_verified_table()
        self.assertTrue(service.valid_check_done)
        self.assertFalse(service.enter_valid)
        self.assertEqual(service.sim.table_key, "id:t1")
        self.assertFalse(service.sim.running, "chưa bật tool thì chưa chạy phiên")
        self.assertIsNone(service.sim.pending, "chưa được arm kế hoạch cược")
        self.assertFalse(service.place_busy, "không được đặt chip")
        self.assertIn("CHƯA đặt cược", service.valid_note)

    def test_enter_valid_rejects_a_different_shoe(self):
        service = self.service()
        self.prime(service, letters="PPPBBB")
        service._auto_start_verified_table()
        self.assertFalse(service.valid_check_done)
        self.assertIsNone(service.auto_start_key, "phải bỏ bàn khác shoe")

    def test_tool_start_arms_the_verified_table(self):
        service = self.service()
        self.prime(service)
        service._auto_start_verified_table()
        reply = service.tool_start()
        self.assertTrue(reply["ok"])
        self.assertTrue(service.sim.running)
        self.assertTrue(service.sim.entry_waiting, "vẫn chờ hết tay hiện tại mới cược")
        self.assertIsNone(service.sim.pending)

    def test_enter_valid_hunts_without_the_tool(self):
        service = self.service()
        service.enter_valid = True
        called = []
        service.open_table = lambda key: called.append(key) or {"ok": True}
        service.auto_open_table(allow_sweep=False)
        self.assertEqual(called, ["id:t1"], "phải mở bàn đạt chuẩn dù tool chưa chạy")

    def test_auto_hunt_alone_still_needs_the_tool(self):
        service = self.service()
        called = []
        service.open_table = lambda key: called.append(key) or {"ok": True}
        service.auto_open_table(allow_sweep=False)
        self.assertEqual(called, [], "không bật tool thì không tự mở bàn")

    def test_cancel_enter_valid_stops_the_hunt(self):
        service = self.service()
        service.enter_valid = True
        service.cycle_waiting = True
        reply = service.cancel_enter_valid()
        self.assertTrue(reply["ok"])
        self.assertFalse(service.enter_valid)
        self.assertFalse(service.cycle_waiting)
        self.assertIn("huỷ", service.valid_note.casefold())

    def test_enter_valid_times_out(self):
        service = self.service()
        service.enter_valid = True
        service.enter_deadline = time.time() - 1
        service._watch_enter_valid(time.time())
        self.assertFalse(service.enter_valid)
        self.assertFalse(service.cycle_waiting)
        self.assertIn("Chưa vào được bàn", service.valid_note)

    def test_snapshot_exposes_enter_valid_state(self):
        service = self.service()
        service.enter_valid = True
        service.enter_deadline = time.time() + 30
        block = service.snapshot()["enter_valid"]
        self.assertTrue(block["active"])
        self.assertFalse(block["checked"])
        self.assertGreater(block["remain"], 0)

    def test_tool_start_without_chrome_enters_valid_flow(self):
        service = self.service()
        service.browser = type("FakeBrowser", (), {
            "connected": False, "note": "", "mode": "cdp", "cdp_attached": False,
            "attach": lambda self: False, "stop": lambda self: None,
        })()
        reply = service.tool_start()
        self.assertTrue(reply["ok"], reply)
        self.assertTrue(service.tool_running)
        self.assertTrue(service.enter_valid, "bật tool phải tự tìm bàn hợp lệ")
        service.cancel_enter_valid()


class ScanTests(unittest.TestCase):
    def test_scan_maps_browser_reports_to_rows(self):
        service = make_service()
        service.browser = type("FakeBrowser", (), {
            "connected": True,
            "scan": lambda self: [{"tables": [{"id": "t1", "title": "Bàn 1",
                                               "letters": "BBBPPP"}]}],
        })()
        snapshot = service.scan()
        self.assertEqual(snapshot["tables"][0]["id"], "t1")
        self.assertEqual(snapshot["tables"][0]["letters"], "BBBPPP")


class ChromeClosedRecoveryTests(unittest.TestCase):
    """Đóng Chrome bằng tay: app phải thấy mất kết nối và còn đường mở lại."""

    def baccarat(self):
        service = engine.BaccaratService(pathlib.Path(tempfile.mkdtemp()) / "b.json",
                                        FakeEmitter())
        table_row(service, "BBBP")
        service.tool_running = True
        return service

    def test_clears_tables_and_pauses_when_chrome_closes(self):
        service = self.baccarat()
        service.browser = fake_browser(connected=True)
        service._on_chrome_lost()
        self.assertEqual(service.tables, {})
        self.assertIn("Mở Chrome", service.sweep_note)
        self.assertTrue(service.offline_noted)

    def test_snapshot_reports_disconnected(self):
        service = self.baccarat()
        service.browser = fake_browser(connected=False, note="Chrome đã đóng")
        snapshot = service.snapshot()
        self.assertFalse(snapshot["chrome_connected"])
        self.assertEqual(snapshot["tables"], [])

    def test_ready_flag_resets_after_reconnect(self):
        service = self.baccarat()
        service._on_chrome_lost()
        self.assertTrue(service.offline_noted)
        service._on_chrome_ready()
        self.assertFalse(service.offline_noted)

    def test_open_chrome_is_idempotent_while_alive(self):
        service = self.baccarat()
        service.browser = fake_browser(connected=True)
        reply = service.chrome_open()
        self.assertTrue(reply["ok"])
        self.assertIn("đã kết nối", reply["note"])

    def test_open_chrome_uses_the_configured_start_url(self):
        service = self.baccarat()
        opened = []
        service.browser = type("FakeBrowser", (), {
            "connected": False, "note": "", "mode": "", "last_diag": {},
            "attach": lambda self: False,
            "start": lambda self, **kwargs: opened.append(kwargs) or True,
            "wait_for_cdp": lambda self: True,
            "resize_window": lambda self, *a, **k: (True, "ok"),
            "driver_info": "CDP 9222",
            "stop": lambda self: None,
        })()
        reply = service.chrome_open()
        self.assertTrue(reply["ok"])
        time.sleep(0.4)                     # luồng mở Chrome chạy nền
        self.assertTrue(opened, "phải gọi mở Chrome")
        self.assertEqual(opened[0]["url"], service.config["start_url"])


class WindowConfigTests(unittest.TestCase):
    def test_config_has_window_and_start_url_by_default(self):
        service = make_service()
        window = service.config["chrome_window"]
        self.assertEqual(sorted(window), ["fullscreen", "height", "percent", "width"])
        self.assertTrue(service.config["start_url"].startswith("http"))

    def test_window_is_clamped_persisted_and_applied(self):
        service = make_service()
        reply = service.apply_window({"width": 99999, "height": 10, "percent": 500,
                                      "fullscreen": True})
        self.assertTrue(reply["ok"])
        window = service.config["chrome_window"]
        self.assertTrue(window["fullscreen"])
        self.assertEqual(window["width"], BR.SIZE_MAX[0])
        self.assertEqual(window["height"], BR.SIZE_MIN[1])
        self.assertEqual(window["percent"], BR.SCALE_MAX)
        saved = json.loads(service.state_path.read_text(encoding="utf-8"))
        self.assertEqual(saved["settings"]["chrome_window"], window)

    def test_invalid_start_url_falls_back_to_the_site(self):
        service = make_service()
        service.config_set({"start_url": "khong-phai-link"})
        self.assertEqual(service.config["start_url"], BR.START_URL)


if __name__ == "__main__":
    unittest.main(verbosity=2)
