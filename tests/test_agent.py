"""agent.py: action parsing, coordinate mapping, hands, the loop, and CLI paths. No network, no phone."""
import argparse, io, json, os, subprocess, sys, tempfile, unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
import agent
from agent import ActionError, Hands, ScreenMap, image_size, parse_action
from tests.helpers import ACTION_JSON, fake_jpeg, fake_png
from tests.test_providers import clean_env


class Images(unittest.TestCase):
    def test_sizes(self):
        self.assertEqual(image_size(fake_jpeg(1920, 1080)), (1920, 1080))
        self.assertEqual(image_size(fake_png(800, 600)), (800, 600))
        with self.assertRaises(ValueError):
            image_size(b"GIF89a....")


class Parsing(unittest.TestCase):
    def test_plain_fenced_prose(self):
        self.assertEqual(parse_action(ACTION_JSON)["combo"], "win+r")
        self.assertEqual(parse_action("```json\n" + ACTION_JSON + "\n```")["type"], "key")
        a = parse_action('Sure! Here is my action: {"type":"click","x":10,"y":20,"reason":"a {brace}"} hope it helps')
        self.assertEqual((a["x"], a["y"], a["button"], a["count"]), (10, 20, "left", 1))

    def test_wrapped_and_aliases(self):
        a = parse_action('{"reason":"r","action":{"type":"type","text":"hi\\n"}}')
        self.assertEqual((a["type"], a["text"], a["reason"]), ("type", "hi\n", "r"))
        self.assertEqual(parse_action('{"action":"key","keys":["ctrl+a","ctrl+c"]}')["combo"], "ctrl+a ctrl+c")

    def test_each_type(self):
        self.assertEqual(parse_action('{"type":"move","dx":"5","dy":-3}')["dx"], 5)
        self.assertEqual(parse_action('{"type":"moveto","x":1,"y":2}')["y"], 2)
        self.assertEqual(parse_action('{"type":"scroll","amount":-500}')["amount"], -50)
        d = parse_action('{"type":"drag","x":1,"y":2,"to_x":3,"to_y":4}')
        self.assertEqual((d["x"], d["to_y"]), (1, 4))
        self.assertEqual(parse_action('{"type":"wait","seconds":99}')["seconds"], 10)
        self.assertFalse(parse_action('{"type":"done","success":false,"summary":"stuck"}')["success"])
        self.assertEqual(parse_action('{"type":"click","button":"right","count":2}')["count"], 2)

    def test_errors(self):
        for bad in ("no json here", '{"type":"explode"}', '{"type":"type"}', '{"type":"click","x":1}',
                    '{"type":"move","dx":"a","dy":1}', '{"type":"click","button":"side"}', '{"type":"key"}'):
            with self.subTest(bad), self.assertRaises(ActionError):
                parse_action(bad)


class Mapping(unittest.TestCase):
    def test_default_is_proportional(self):
        m = ScreenMap()
        self.assertEqual(m.to_fraction(0, 0, 1281, 721), (0.0, 0.0))
        self.assertEqual(m.to_fraction(640, 360, 1281, 721), (0.5, 0.5))
        self.assertEqual(m.to_fraction(-5, 9999, 100, 100), (0.0, 1.0))

    def test_homography_corners(self):
        corners = [(100, 50), (1180, 80), (1150, 690), (130, 660)]
        m = ScreenMap(corners)
        for (x, y), want in zip(corners, [(0, 0), (1, 0), (1, 1), (0, 1)]):
            fx, fy = m.to_fraction(x, y, 1280, 720)
            self.assertAlmostEqual(fx, want[0], places=6)
            self.assertAlmostEqual(fy, want[1], places=6)

    def test_parse_cli_values(self):
        self.assertEqual(agent.parse_corners("0,0 10,0 10,10 0,10")[2], (10.0, 10.0))
        self.assertEqual(agent.parse_size("2560x1440"), (2560, 1440))
        with self.assertRaises(argparse.ArgumentTypeError):
            agent.parse_corners("1,2,3")


class FakeCall:
    def __init__(self):
        self.calls = []

    def __call__(self, path, params):
        self.calls.append((path, params))
        return {"ok": True}


class HandsTest(unittest.TestCase):
    def test_absolute_click(self):
        f = FakeCall()
        h = Hands(ScreenMap(), "absolute", (1920, 1080), call=f)
        self.assertTrue(h.run(parse_action('{"type":"click","x":640,"y":360}'), 1281, 721)["ok"])
        self.assertEqual(f.calls, [("/moveto", {"fx": 0.5, "fy": 0.5}), ("/click", {"button": "left", "count": 1})])

    def test_relative_click_homes_then_moves(self):
        f = FakeCall()
        h = Hands(ScreenMap(), "relative", (1920, 1080), gain=1.0, call=f)
        h.run(parse_action('{"type":"click","x":640,"y":360}'), 1281, 721)
        self.assertEqual(f.calls[0][0], "/move")
        self.assertLess(f.calls[0][1]["dx"], -1920)
        self.assertEqual((f.calls[1][1]["dx"], f.calls[1][1]["dy"]), (960, 540))
        self.assertEqual(f.calls[2][0], "/click")

    def test_drag_relative(self):
        f = FakeCall()
        h = Hands(ScreenMap(), "relative", (1000, 1000), call=f)
        r = h.run(parse_action('{"type":"drag","x":0,"y":0,"to_x":50,"to_y":0}'), 101, 101)
        self.assertTrue(r["ok"])
        paths = [c[0] for c in f.calls]
        self.assertEqual(paths, ["/move", "/move", "/down", "/move", "/up"])
        self.assertEqual(f.calls[3][1]["dx"], 500)

    def test_keys_text_scroll(self):
        f = FakeCall()
        h = Hands(ScreenMap(), "absolute", (1920, 1080), call=f)
        h.run(parse_action('{"type":"key","combo":"Ctrl+L"}'), 10, 10)
        h.run(parse_action('{"type":"type","text":"hello"}'), 10, 10)
        h.run(parse_action('{"type":"scroll","amount":-3}'), 10, 10)
        self.assertEqual(f.calls, [("/key", {"combo": "ctrl+l"}), ("/type", {"text": "hello"}),
                                   ("/scroll", {"amount": -3})])

    def test_refuses_secrets(self):
        f = FakeCall()
        h = Hands(ScreenMap(), "absolute", (1920, 1080), call=f)
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": "not-a-real-key-123456"}):
            r = h.run({"type": "type", "text": "my key is not-a-real-key-123456", "reason": ""}, 10, 10)
        self.assertFalse(r["ok"])
        r = h.run({"type": "type", "text": "AKIA" + "ABCDEFGHIJKLMNOP", "reason": ""}, 10, 10)
        self.assertFalse(r["ok"])
        self.assertEqual(f.calls, [])

    def test_dry_run_sends_nothing(self):
        h = Hands(ScreenMap(), "absolute", (1920, 1080), dry_run=True, call=mock.Mock(side_effect=AssertionError))
        with redirect_stdout(io.StringIO()):
            self.assertTrue(h.run(parse_action('{"type":"click","x":1,"y":1}'), 10, 10)["dryRun"])


class FakeBrain:
    def __init__(self, replies):
        self.replies, self.seen = list(replies), []

    def complete(self, system, user, image):
        self.seen.append((system, user, image))
        return self.replies.pop(0)


def loop_args(**kw):
    a = dict(goal="open notepad", max_steps=5, history=8, dry_run=False, confirm=False, settle=0, log=None,
             image="x.jpg", save_frames=None, corners=None)
    a.update(kw)
    return argparse.Namespace(**a)


class Loop(unittest.TestCase):
    def test_act_then_done(self):
        brain = FakeBrain([ACTION_JSON, "garbage", '{"type":"type","text":"notepad\\n"}',
                           '{"type":"done","summary":"opened"}'])
        f = FakeCall()
        out = []
        rc = agent.run(loop_args(), brain, Hands(ScreenMap(), "absolute", (1920, 1080), call=f),
                       lambda p: fake_jpeg(), out=out.append)
        self.assertEqual(rc, 0)
        self.assertEqual([c[0] for c in f.calls], ["/key", "/type"])
        sys_p, last_user, img = brain.seen[-1]
        self.assertIn("1280x720", sys_p)
        self.assertIn("Never type passwords", sys_p)
        self.assertIn("step 1: key", last_user)
        self.assertIn("invalid reply", last_user)
        self.assertEqual(img, fake_jpeg())

    def test_confirm_skip_and_quit(self):
        brain = FakeBrain([ACTION_JSON, ACTION_JSON])
        f = FakeCall()
        answers = iter(["n", "q"])
        rc = agent.run(loop_args(confirm=True), brain, Hands(ScreenMap(), "absolute", (1920, 1080), call=f),
                       lambda p: fake_jpeg(), out=lambda *_: None, ask=lambda _: next(answers))
        self.assertEqual(rc, 1)
        self.assertEqual(f.calls, [])
        self.assertIn("SKIPPED", brain.seen[1][1])

    def test_max_steps(self):
        brain = FakeBrain([ACTION_JSON] * 3)
        rc = agent.run(loop_args(max_steps=3), brain, Hands(ScreenMap(), "absolute", (1, 1), call=FakeCall()),
                       lambda p: fake_jpeg(), out=lambda *_: None)
        self.assertEqual(rc, 1)


class CLI(unittest.TestCase):
    def main(self, argv, env=None):
        out, err = io.StringIO(), io.StringIO()
        with clean_env(env), redirect_stdout(out), redirect_stderr(err):
            rc = agent.main(argv)
        return rc, out.getvalue(), err.getvalue()

    def test_help_subprocess(self):
        r = subprocess.run([sys.executable, os.path.join(ROOT, "agent.py"), "--help"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)
        self.assertIn("--dry-run", r.stdout)
        self.assertIn("aws-bedrock", r.stdout)

    def test_list_providers(self):
        rc, out, _ = self.main(["--list-providers"])
        self.assertEqual(rc, 0)
        self.assertIn("openai-compatible", out)

    def test_missing_key_is_graceful(self):
        rc, _, err = self.main(["--provider", "anthropic", "--dry-run", "x"])
        self.assertEqual(rc, 2)
        self.assertIn("ANTHROPIC_API_KEY", err)

    def test_dry_run_without_phone_is_graceful(self):
        rc, _, err = self.main(["--provider", "xai", "--dry-run", "x"], {"XAI_API_KEY": "test", "PHONE_SERIAL": ""})
        self.assertEqual(rc, 2)
        self.assertIn("phone not ready", err)
        self.assertIn("PHONE_SERIAL", err)

    def test_dry_run_with_image_end_to_end(self):
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as f:
            f.write(fake_jpeg())
        self.addCleanup(os.unlink, f.name)
        replies = [{"choices": [{"message": {"content": '{"type":"click","x":5,"y":5,"reason":"ok button"}'}}]},
                   {"choices": [{"message": {"content": '{"type":"done","summary":"clicked"}'}}]}]
        with mock.patch("providers.http_post_json", side_effect=replies) as post:
            rc, out, err = self.main(["--provider", "openai-compatible", "--base-url", "http://localhost:1/v1",
                                      "--image", f.name, "--dry-run", "click ok"])
        self.assertEqual(rc, 0, err)
        self.assertEqual(post.call_count, 2)
        self.assertIn("[dry-run] /move", out)
        self.assertIn("DONE (success)", out)


if __name__ == "__main__":
    unittest.main()
