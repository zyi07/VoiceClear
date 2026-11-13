#!/usr/bin/env python3
"""tools 目录下的数据与评测工具单元测试。

运行：python3 tools/test_tools.py

这些测试保证评测指标的定义不会在后续迭代中被无声改坏——
指标口径一旦漂移，历史结论就不可比了。
"""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
VALIDATOR = TOOLS_DIR / "validate_dataset.py"
EVALUATOR = TOOLS_DIR / "eval_asr.py"


def run_script(script, *args):
    return subprocess.run([sys.executable, str(script), *args], capture_output=True, text=True, check=False)


def import_tool(module_name):
    import importlib.util
    spec = importlib.util.spec_from_file_location(module_name, TOOLS_DIR / (module_name + ".py"))
    module = importlib.util.module_from_spec(spec)
    # dataclass 装饰器要求模块已注册到 sys.modules，故必须先注册再执行。
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


class NormalizeTest(unittest.TestCase):
    """CER 的分母口径必须稳定。"""

    def setUp(self):
        self.eval_asr = import_tool("eval_asr")

    def test_removes_punctuation(self):
        text = "我明天上午十点去医院复诊。"
        self.assertEqual("我明天上午十点去医院复诊", self.eval_asr.normalize(text))

    def test_removes_whitespace(self):
        text = "今天 天气\t不错"
        self.assertEqual("今天天气不错", self.eval_asr.normalize(text))

    def test_keeps_chinese_characters(self):
        self.assertEqual("三十二岁", self.eval_asr.normalize("三十二岁"))


class CriticalErrorTest(unittest.TestCase):
    """关键内容错误的判定直接关系到内容保真。"""

    def setUp(self):
        self.eval_asr = import_tool("eval_asr")

    def test_identical_text_has_no_critical_error(self):
        self.assertFalse(self.eval_asr.has_critical_error("我不去", "我不去"))

    def test_dropped_negation_is_critical(self):
        self.assertTrue(self.eval_asr.has_critical_error("我不去", "我去"))

    def test_changed_number_is_critical(self):
        self.assertTrue(self.eval_asr.has_critical_error("每次两片", "每次三片"))

    def test_changed_time_is_critical(self):
        self.assertTrue(self.eval_asr.has_critical_error("上午十点", "下午十点"))

    def test_missing_clause_is_critical(self):
        self.assertTrue(self.eval_asr.has_critical_error("这个不是我要的，请换一个", "这个不是我要的"))


class ValidatorTest(unittest.TestCase):
    """数据集校验必须能拦住真实违规。"""

    def make_dataset(self, root, transcripts, splits):
        for name in ("consent", "speakers", "transcripts", "splits"):
            (root / name).mkdir(parents=True, exist_ok=True)
        manifest = {
            "datasetVersion": "v0.1.0",
            "cohort": {"target": "test"},
            "splits": {"train": [], "dev": [], "test": []},
            "totals": {"speakers": 1},
            "license": {"code": "internal", "commercialUse": False},
            "limitations": ["测试数据"],
        }
        (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        consent = {
            "speakerId": "spk_001",
            "grants": {
                "research_use": True,
                "train_use": True,
                "cloud_upload": False,
                "voice_profile": False,
                "anonymous_report": True,
            },
        }
        consent_path = root / "consent" / "spk_001.json"
        consent_path.write_text(json.dumps(consent, ensure_ascii=False), encoding="utf-8")
        speaker = {
            "speakerId": "spk_001",
            "severityLevel": "L2",
            "severitySource": "professional_advisor",
            "sessionCount": 3,
        }
        speaker_path = root / "speakers" / "spk_001.json"
        speaker_path.write_text(json.dumps(speaker, ensure_ascii=False), encoding="utf-8")
        transcript_path = root / "transcripts" / "ses_001.jsonl"
        transcript_path.write_text(transcripts, encoding="utf-8")
        for split_name, members in splits.items():
            content = "\n".join(members)
            if members:
                content = content + "\n"
            split_path = root / "splits" / (split_name + ".txt")
            split_path.write_text(content, encoding="utf-8")
        return root

    def valid_segment(self, **overrides):
        record = {
            "segmentId": "seg_001",
            "sessionId": "ses_001",
            "speakerId": "spk_001",
            "sessionDate": "2026-11-20",
            "scene": "read",
            "promptId": "P0001",
            "referenceText": "今天天气不错。",
            "referenceSource": "approved_prompt",
            "durationMs": 1500,
            "qualityFlags": [],
            "consentFlags": ["research_use", "train_use"],
        }
        record.update(overrides)
        return json.dumps(record, ensure_ascii=False) + "\n"

    def test_valid_dataset_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(), {"train": ["spk_001"], "dev": [], "test": []})
            result = run_script(VALIDATOR, str(root))
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn("校验通过", result.stdout)

    def test_split_overlap_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(), {"train": ["spk_001"], "dev": [], "test": ["spk_001"]})
            result = run_script(VALIDATOR, str(root))
            self.assertEqual(1, result.returncode)
            self.assertIn("划分重叠", result.stdout)

    def test_unauthorized_use_is_rejected(self):
        segment = self.valid_segment(consentFlags=["research_use", "cloud_upload"])
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), segment, {"train": ["spk_001"], "dev": [], "test": []})
            result = run_script(VALIDATOR, str(root))
            self.assertEqual(1, result.returncode)
            self.assertIn("未授权", result.stdout)

    def test_empty_reference_is_rejected(self):
        segment = self.valid_segment(scene="spontaneous", referenceText="", referenceSource="user_confirmed")
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), segment, {"train": ["spk_001"], "dev": [], "test": []})
            result = run_script(VALIDATOR, str(root))
            self.assertEqual(1, result.returncode)
            self.assertIn("referenceText", result.stdout)

    def test_invalid_severity_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(), {"train": ["spk_001"], "dev": [], "test": []})
            speaker_path = root / "speakers" / "spk_001.json"
            speaker_path.write_text(json.dumps({"speakerId": "spk_001", "severityLevel": "L9"}, ensure_ascii=False), encoding="utf-8")
            result = run_script(VALIDATOR, str(root))
            self.assertEqual(1, result.returncode)
            self.assertIn("severityLevel", result.stdout)


class EvaluatorTest(unittest.TestCase):
    """评测脚本必须给出可复核的数字。"""

    def run_eval(self, hyp_text, ref_text):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            hyp = root / "hyp.jsonl"
            ref = root / "ref.jsonl"
            hyp.write_text(hyp_text, encoding="utf-8")
            ref.write_text(ref_text, encoding="utf-8")
            return run_script(EVALUATOR, "--hyp", str(hyp), "--ref", str(ref))

    def test_reports_cer_and_layers(self):
        hyp = json.dumps({"segmentId": "s1", "text": "今天天气不错"}, ensure_ascii=False) + "\n"
        ref = json.dumps({
            "segmentId": "s1",
            "referenceText": "今天天气不错",
            "speakerId": "spk_001",
            "severityLevel": "L2",
            "category": "daily",
        }, ensure_ascii=False) + "\n"
        result = self.run_eval(hyp, ref)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("CER = 0.0000", result.stdout)
        self.assertIn("按说话人分层", result.stdout)
        self.assertIn("探索性观察", result.stdout)

    def test_flags_critical_error(self):
        hyp = json.dumps({"segmentId": "s1", "text": "我去"}, ensure_ascii=False) + "\n"
        ref = json.dumps({
            "segmentId": "s1",
            "referenceText": "我不去",
            "speakerId": "spk_001",
            "severityLevel": "L2",
            "category": "negation",
        }, ensure_ascii=False) + "\n"
        result = self.run_eval(hyp, ref)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("关键内容错误率 = 1.0000", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
