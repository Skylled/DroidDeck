import os
from pathlib import Path
import runpy
import struct
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
MODULE = runpy.run_path(str(BIN / "droiddeck-game-env"))


class HalfLifeAlyxGameEnvTest(unittest.TestCase):
    def test_applies_vr_defaults_by_app_id_when_vr_present(self):
        with mock.patch.dict(os.environ, {"DROIDDECK_VR_PRESENT": "questwsi"}, clear=False):
            env, extra, notes = MODULE["engine_fixes"]("/games/hlvr/game/bin/win64/hlvr.exe", [], "546560")
        self.assertEqual({}, env)
        self.assertEqual(
            [
                "-novid",
                "-vulkan",
                "+vr_fidelity_level_auto",
                "0",
                "+vr_fidelity_level",
                "1",
                "+vr_render_scale",
                "0.65",
            ],
            extra,
        )
        self.assertTrue(any("Half-Life: Alyx" in n for n in notes))

    def test_applies_vr_defaults_by_stem_with_vr_override(self):
        with mock.patch.dict(os.environ, {"VR_OVERRIDE": "/opt/steamvr"}, clear=False):
            os.environ.pop("DROIDDECK_VR_PRESENT", None)
            _, extra, notes = MODULE["engine_fixes"]("/games/hlvr/hlvr.exe", [], "")
        self.assertIn("-vulkan", extra)
        self.assertIn("+vr_render_scale", extra)
        self.assertTrue(any("Half-Life: Alyx" in n for n in notes))

    def test_applies_vr_defaults_when_vr_arg_passed(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            _, extra, _ = MODULE["engine_fixes"]("/games/hlvr/hlvr.exe", ["-vr"], "546560")
        self.assertEqual(
            [
                "-novid",
                "-vulkan",
                "+vr_fidelity_level_auto",
                "0",
                "+vr_fidelity_level",
                "1",
                "+vr_render_scale",
                "0.65",
            ],
            extra,
        )

    def test_skips_when_not_in_vr_mode(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            _, extra, notes = MODULE["engine_fixes"]("/games/hlvr/hlvr.exe", [], "546560")
        self.assertEqual([], extra)
        self.assertFalse(any("Half-Life: Alyx" in n for n in notes))

    def test_respects_user_overrides(self):
        user_args = ["-vr", "-novid", "-dx11", "+vr_render_scale=0.8", "+vr_fidelity_level", "3"]
        with mock.patch.dict(os.environ, {}, clear=True):
            _, extra, _ = MODULE["engine_fixes"]("/games/hlvr/hlvr.exe", user_args, "546560")
        self.assertEqual(["+vr_fidelity_level_auto", "0"], extra)

    def test_returns_nothing_when_all_flags_set(self):
        user_args = [
            "-vr",
            "-novid",
            "-vulkan",
            "+vr_fidelity_level_auto",
            "0",
            "+vr_fidelity_level",
            "2",
            "+vr_render_scale",
            "0.75",
        ]
        with mock.patch.dict(os.environ, {}, clear=True):
            _, extra, notes = MODULE["engine_fixes"]("/games/hlvr/hlvr.exe", user_args, "546560")
        self.assertEqual([], extra)
        self.assertFalse(any("Half-Life: Alyx" in n for n in notes))

    def test_native_arm64_build_keeps_valves_defaults(self):
        with tempfile.TemporaryDirectory() as tmp, \
                mock.patch.dict(os.environ, {"DROIDDECK_VR_PRESENT": "questwsi"}, clear=False):
            for folder, machine in (("linuxarm64", 183), ("linuxsteamrt64", 62)):
                exe = Path(tmp) / "game/bin" / folder / "hlvr"
                exe.parent.mkdir(parents=True)
                head = bytearray(64)
                head[0:6] = b"\x7fELF\x02\x01"
                struct.pack_into("<H", head, 18, machine)
                exe.write_bytes(bytes(head))
            _, extra, notes = MODULE["engine_fixes"](str(Path(tmp) / "game/bin/linuxarm64/hlvr"), ["-vr"], "546560")
            self.assertEqual([], extra)
            self.assertFalse(any("Half-Life: Alyx" in n for n in notes))
            self.assertEqual([], MODULE["engine_fixes"]("/elsewhere/linuxarm64/hlvr", ["-vr"], "546560")[1])
            _, extra, _ = MODULE["engine_fixes"](str(Path(tmp) / "game/bin/linuxsteamrt64/hlvr"), ["-vr"], "546560")
            self.assertIn("+vr_render_scale", extra)

    def test_does_not_affect_other_games(self):
        with mock.patch.dict(os.environ, {"DROIDDECK_VR_PRESENT": "questwsi"}, clear=False):
            _, extra, notes = MODULE["engine_fixes"]("/games/other/other.exe", ["-vr"], "440")
        self.assertEqual([], extra)
        self.assertFalse(any("Half-Life: Alyx" in n for n in notes))


if __name__ == "__main__":
    unittest.main()
