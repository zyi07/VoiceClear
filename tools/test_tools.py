#!/usr/bin/env python3
"""VoiceClear 数据、导入与评测生产工具的真实单元测试。

运行：python3 -m unittest tools.test_tools -v
"""

from __future__ import annotations

import contextlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parent
VALIDATOR = TOOLS_DIR / "validate_dataset.py"
EVALUATOR = TOOLS_DIR / "eval_asr.py"
IMPORTER = TOOLS_DIR / "import_session.py"
ASR_CHECKER = TOOLS_DIR / "check_asr_config.py"


def run_script(script, *args):
    return subprocess.run(
        [sys.executable, str(script), *args],
        capture_output=True,
        text=True,
        check=False,
    )


def import_tool(module_name):
    import importlib.util

    spec = importlib.util.spec_from_file_location(module_name, TOOLS_DIR / (module_name + ".py"))
    module = importlib.util.module_from_spec(spec)
    # dataclass 装饰器要求模块已注册到 sys.modules，故必须先注册再执行。
    sys.modules[module_name] = module
    spec.loader.exec_module(module)
    return module


def jsonl(record):
    return json.dumps(record, ensure_ascii=False) + "\n"


def tree_bytes(root: Path):
    return {
        str(path.relative_to(root)): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file()
    }


class DatasetFactory:
    """生成符合真实 App 目录布局和 PCM 时长的数据集，而非绕开生产断言。"""

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
            "sampleRate": 16000,
            "channels": 1,
            "encoding": "pcm_s16",
            "qualityFlags": [],
            "consentFlags": ["research_use", "train_use"],
        }
        record.update(overrides)
        return record

    def make_dataset(
        self,
        root: Path,
        records,
        splits=None,
        *,
        consent_overrides=None,
        missing_audio_ids=None,
    ):
        if isinstance(records, dict):
            records = [records]
        records = list(records)
        missing_audio_ids = missing_audio_ids or set()
        for name in ("consent", "speakers", "transcripts", "splits", "segments", "prompts"):
            (root / name).mkdir(parents=True, exist_ok=True)
        sessions = sorted({record.get("sessionId", "ses_001") for record in records})
        manifest = {
            "datasetVersion": "v0.1.0",
            "cohort": {"target": "test"},
            "speakers": [{"speakerId": "spk_001", "severityLevel": "L2", "sessions": len(sessions), "segments": len(records)}],
            "splits": splits or {"train": ["spk_001"], "dev": [], "test": []},
            "totals": {"speakers": 1, "sessions": len(sessions), "segments": len(records), "hours": 0.0},
            "license": {"code": "internal", "commercialUse": False},
            "limitations": ["测试数据"],
        }
        (root / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
        consent = {
            "speakerId": "spk_001",
            "signedAt": "2026-11-20T10:00:00+08:00",
            "grants": {
                "research_use": True,
                "train_use": True,
                "cloud_upload": False,
                "voice_profile": False,
                "anonymous_report": True,
            },
            "withdrawnAt": None,
        }
        consent.update(consent_overrides or {})
        (root / "consent" / "spk_001.json").write_text(json.dumps(consent, ensure_ascii=False), encoding="utf-8")
        speaker = {
            "speakerId": "spk_001",
            "severityLevel": "L2",
            "severitySource": "professional_advisor",
            "sessionCount": 3,
        }
        (root / "speakers" / "spk_001.json").write_text(json.dumps(speaker, ensure_ascii=False), encoding="utf-8")

        by_session = {}
        for record in records:
            by_session.setdefault(record["sessionId"], []).append(record)
            if record["segmentId"] not in missing_audio_ids:
                audio = root / "segments" / record["sessionId"] / f"{record['segmentId']}.pcm"
                audio.parent.mkdir(parents=True, exist_ok=True)
                byte_count = record["sampleRate"] * record["channels"] * 2 * record["durationMs"] // 1000
                audio.write_bytes(b"\0" * byte_count)
        for session_id, session_records in by_session.items():
            content = "".join(jsonl(record) for record in session_records)
            (root / "transcripts" / f"{session_id}.jsonl").write_text(content, encoding="utf-8")
        for split_name, members in manifest["splits"].items():
            content = "\n".join(members)
            (root / "splits" / f"{split_name}.txt").write_text(content + ("\n" if content else ""), encoding="utf-8")
        prompts = {"prompts": [{"id": "P0001", "category": "daily", "text": "今天天气不错。"}]}
        (root / "prompts" / "prompts.json").write_text(json.dumps(prompts, ensure_ascii=False), encoding="utf-8")
        shandong_prompts = {
            "prompts": [{"id": "SD0001", "category": "daily", "text": "今天早上有点凉。"}]
        }
        (root / "prompts" / "shandong.json").write_text(
            json.dumps(shandong_prompts, ensure_ascii=False), encoding="utf-8"
        )
        return root


class AsrConfigTest(unittest.TestCase):
    def setUp(self):
        self.checker = import_tool("check_asr_config")

    def source(self, feature="80", tokens='"tokens.txt"', language='"zh"'):
        return f"""
            const val SAMPLE_RATE = 16_000
            const val FEATURE_DIM = {feature}
            const val LANGUAGE = {language}
            const val TOKENS_FILE = {tokens}
            const val MODEL_FILE = "model." + "int8.onnx"
        """

    def check_source(self, source):
        with contextlib.redirect_stdout(io.StringIO()):
            return self.checker.check_source(source)

    def test_project_constants_and_cli_pass(self):
        source = self.checker.ENGINE.read_text(encoding="utf-8")
        self.assertEqual([], self.check_source(source))
        result = run_script(ASR_CHECKER)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("5 项参数符合项目固定配置", result.stdout)

    def test_language_is_intentionally_fixed_to_zh(self):
        self.assertEqual("zh", self.checker.EXPECTED["LANGUAGE"])
        self.assertEqual([], self.check_source(self.source(language='"zh"')))
        problems = self.check_source(self.source(language='""'))
        self.assertEqual(1, len(problems))
        self.assertIn("LANGUAGE", problems[0])

    def test_integer_arithmetic_is_fully_evaluated(self):
        parse = self.checker.parse_kotlin_constant
        self.assertEqual(560, parse("80 * 7"))
        self.assertEqual(88, parse("(40 + 8) * 2 - 16 / 2"))
        self.assertEqual(16000, parse("16_000L"))
        self.assertEqual(80, parse("0x50"))

    def test_kotlin_integer_division_and_remainder_truncate_toward_zero(self):
        parse = self.checker.parse_kotlin_constant
        self.assertEqual(-3, parse("-7 / 2"))
        self.assertEqual(-1, parse("-7 % 2"))
        self.assertEqual(-3, parse("7 / -2"))
        self.assertEqual(1, parse("7 % -2"))

    def test_strings_comments_and_concatenation(self):
        parse = self.checker.parse_kotlin_constant
        self.assertEqual("model.int8.onnx", parse('"model." + /* name */ "int8.onnx"'))
        self.assertEqual(80, parse("40 * 2 // feature dimension"))
        self.assertEqual("", parse('""'))

    def test_multiplied_wrong_value_is_not_misread_as_prefix(self):
        problems = self.check_source(self.source(feature="80 * 7"))
        self.assertEqual(1, len(problems))
        self.assertIn("FEATURE_DIM", problems[0])
        self.assertIn("560", problems[0])
        self.assertIn("80", problems[0])

    def test_correct_arithmetic_expression_is_accepted(self):
        self.assertEqual([], self.check_source(self.source(feature="(40 + 8) * 2 - 16")))

    def test_string_prefix_does_not_count_as_equal(self):
        problems = self.check_source(self.source(tokens='"tokens.txt.unsafe"'))
        self.assertEqual(1, len(problems))
        self.assertIn("TOKENS_FILE", problems[0])

    def test_calls_properties_templates_and_trailing_content_are_rejected(self):
        invalid = [
            '80 * java.lang.Runtime.getRuntime()',
            'ErrorCodes.FEATURE_DIM',
            '"${danger()}"',
            '80 trailing',
            '"tokens.txt" + dangerous()',
        ]
        for expression in invalid:
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    self.checker.parse_kotlin_constant(expression)

    def test_malformed_expressions_fail_closed(self):
        for expression in ("", "80 +", "(80", "1 / 0", "1 % 0", '"unterminated'):
            with self.subTest(expression=expression):
                with self.assertRaises(ValueError):
                    self.checker.parse_kotlin_constant(expression)

    def test_unsupported_expression_cannot_execute_python(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "executed"
            expression = f'__import__("pathlib").Path("{marker}").write_text("bad")'
            with self.assertRaises(ValueError):
                self.checker.parse_kotlin_constant(expression)
            self.assertFalse(marker.exists())


class NormalizeTest(unittest.TestCase):
    def setUp(self):
        self.eval_asr = import_tool("eval_asr")

    def test_removes_punctuation(self):
        self.assertEqual("我明天上午十点去医院复诊", self.eval_asr.normalize("我明天上午十点去医院复诊。"))

    def test_removes_whitespace(self):
        self.assertEqual("今天天气不错", self.eval_asr.normalize("今天 天气\t不错"))

    def test_keeps_chinese_characters(self):
        self.assertEqual("三十二岁", self.eval_asr.normalize("三十二岁"))


class CriticalErrorTest(unittest.TestCase):
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


class ValidatorTest(unittest.TestCase, DatasetFactory):
    def validate(self, root):
        return run_script(VALIDATOR, str(root))

    def make_default(self, root, **kwargs):
        return self.make_dataset(root, self.valid_segment(), **kwargs)

    def test_valid_dataset_with_real_audio_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(Path(tmp))
            result = self.validate(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("校验通过", result.stdout)
            self.assertIn("有效: 1", result.stdout)

    def test_split_overlap_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(
                Path(tmp), splits={"train": ["spk_001"], "dev": [], "test": ["spk_001"]}
            )
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("划分重叠", result.stdout)

    def test_unauthorized_use_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(Path(tmp))
            segment = self.valid_segment(consentFlags=["research_use", "cloud_upload"])
            root = self.make_dataset(Path(tmp), segment)
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("未授权", result.stdout)

    def test_empty_segment_consent_flags_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(consentFlags=[]))
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("consentFlags 必须明确包含 research_use", result.stdout)
            self.assertIn("有效: 0", result.stdout)

    def test_segment_research_use_must_be_explicit_even_when_speaker_is_authorized(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(consentFlags=["train_use"]))
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("consentFlags 必须明确包含 research_use", result.stdout)
            self.assertIn("有效: 0", result.stdout)

    def test_empty_reference_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            segment = self.valid_segment(scene="spontaneous", referenceText="", referenceSource="user_confirmed")
            root = self.make_dataset(Path(tmp), segment)
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("referenceText", result.stdout)

    def test_punctuation_only_reference_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_dataset(Path(tmp), self.valid_segment(referenceText="。"))
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("规范化后为空", result.stdout)

    def test_invalid_severity_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(Path(tmp))
            (root / "speakers" / "spk_001.json").write_text(
                json.dumps({"speakerId": "spk_001", "severityLevel": "L9"}, ensure_ascii=False),
                encoding="utf-8",
            )
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("severityLevel", result.stdout)

    def test_withdrawn_consent_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(
                Path(tmp), consent_overrides={"withdrawnAt": "2026-11-25T10:00:00+08:00"}
            )
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("撤回", result.stdout)
            self.assertIn("有效: 0", result.stdout)

    def test_unconfirmed_and_empty_capture_are_not_valid(self):
        with tempfile.TemporaryDirectory() as tmp:
            records = [
                self.valid_segment(referenceSource="unconfirmed"),
                self.valid_segment(segmentId="seg_002", qualityFlags=["empty_capture"]),
            ]
            root = self.make_dataset(Path(tmp), records)
            result = self.validate(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("有效: 0", result.stdout)
            self.assertIn("未确认意图(不计入正确率): 1", result.stdout)
            self.assertIn("空采集: 1", result.stdout)

    def test_empty_capture_still_checks_consent_and_audio(self):
        with tempfile.TemporaryDirectory() as tmp:
            record = self.valid_segment(qualityFlags=["empty_capture"], consentFlags=["cloud_upload"])
            root = self.make_dataset(Path(tmp), record, missing_audio_ids={"seg_001"})
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("未授权", result.stdout)
            self.assertIn("音频不存在", result.stdout)

    def test_duplicate_segment_id_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            records = [self.valid_segment(), self.valid_segment(referenceText="另一句")]
            root = self.make_dataset(Path(tmp), records)
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("segmentId 重复", result.stdout)
            self.assertIn("有效: 0", result.stdout)

    def test_missing_audio_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(Path(tmp), missing_audio_ids={"seg_001"})
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("音频不存在", result.stdout)
            self.assertIn("有效: 0", result.stdout)

    def test_pcm_duration_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.make_default(Path(tmp))
            audio = root / "segments" / "ses_001" / "seg_001.pcm"
            audio.write_bytes(audio.read_bytes()[:1000])
            result = self.validate(root)
            self.assertEqual(1, result.returncode)
            self.assertIn("实际时长", result.stdout)

    def test_shandong_profile_and_dialect_group_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            segment = self.valid_segment(
                promptId="SD0001",
                speechProfileId="shandong",
                localeTag="zh-CN",
                dialectGroup="jilu",
            )
            root = self.make_dataset(Path(tmp), segment)
            result = self.validate(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("校验通过", result.stdout)

    def test_shandong_unspecified_group_warns_for_backfill(self):
        with tempfile.TemporaryDirectory() as tmp:
            segment = self.valid_segment(speechProfileId="shandong", dialectGroup="unspecified")
            root = self.make_dataset(Path(tmp), segment)
            result = self.validate(root)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("dialectGroup 尚未确认", result.stdout)
            self.assertIn("补录", result.stdout)

    def test_invalid_speech_metadata_is_rejected(self):
        cases = (
            ({"speechProfileId": "guan_hua"}, "speechProfileId"),
            ({"localeTag": "zh-SD"}, "localeTag"),
            ({"dialectGroup": "qingdao"}, "dialectGroup"),
            ({"dialectGroup": "jilu"}, "mandarin 的 dialectGroup"),
        )
        for override, expected in cases:
            with self.subTest(expected=expected), tempfile.TemporaryDirectory() as tmp:
                root = self.make_dataset(Path(tmp), self.valid_segment(**override))
                result = self.validate(root)
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn(expected, result.stdout)
                self.assertIn("有效: 0", result.stdout)


class EvaluatorTest(unittest.TestCase, DatasetFactory):
    def run_eval(self, hyp_text, ref_text, *extra):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            hyp = root / "hyp.jsonl"
            ref = root / "ref.jsonl"
            hyp.write_text(hyp_text, encoding="utf-8")
            ref.write_text(ref_text, encoding="utf-8")
            return run_script(EVALUATOR, "--hyp", str(hyp), "--ref", str(ref), *extra)

    def ref_record(self, segment_id="s1", text="今天天气不错", **overrides):
        record = {
            "segmentId": segment_id,
            "sessionId": "ses_001",
            "referenceText": text,
            "speakerId": "spk_001",
            "severityLevel": "L2",
            "category": "daily",
        }
        record.update(overrides)
        return record

    def hyp_record(self, segment_id="s1", text="今天天气不错"):
        return {"segmentId": segment_id, "text": text}

    def run_dataset_eval(self, record, consent_overrides=None):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = self.make_dataset(
                Path(tmp) / "dataset", record, consent_overrides=consent_overrides
            )
            hyp = Path(tmp) / "hyp.jsonl"
            hyp.write_text(jsonl({"segmentId": record["segmentId"], "text": "今天天气不错"}), encoding="utf-8")
            return run_script(
                EVALUATOR,
                "--hyp", str(hyp),
                "--ref", str(dataset / "transcripts" / f"{record['sessionId']}.jsonl"),
                "--manifest", str(dataset / "manifest.json"),
            )

    def test_reports_cer_and_layers(self):
        result = self.run_eval(jsonl(self.hyp_record()), jsonl(self.ref_record()))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("CER = 0.0000", result.stdout)
        self.assertIn("按说话人分层", result.stdout)
        self.assertIn("按 speechProfileId 分层", result.stdout)
        self.assertIn("mandarin", result.stdout)
        self.assertIn("dialectGroup=unspecified", result.stdout)
        self.assertIn("探索性观察", result.stdout)
        self.assertIn("matched=1  missing=0  extra=0", result.stdout)
        self.assertIn("有效参考覆盖率 = 100.00%", result.stdout)

    def test_reports_profile_and_nested_dialect_group_metrics(self):
        hyp = jsonl(self.hyp_record()) + jsonl(self.hyp_record(segment_id="s2", text="我去"))
        ref = jsonl(self.ref_record()) + jsonl(self.ref_record(
            segment_id="s2",
            text="我不去",
            category="negation",
            speechProfileId="shandong",
            localeTag="zh-CN",
            dialectGroup="jilu",
        ))
        result = self.run_eval(hyp, ref)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("  mandarin  片段 1  CER 0.0000", result.stdout)
        self.assertIn("  shandong  片段 1  CER", result.stdout)
        self.assertIn("dialectGroup=unspecified  片段 1", result.stdout)
        self.assertIn("dialectGroup=jilu  片段 1", result.stdout)
        self.assertIn("关键错误率 1.0000", result.stdout)

    def test_invalid_profile_metadata_fails_evaluation(self):
        ref = jsonl(self.ref_record(speechProfileId="shandong", dialectGroup="invented"))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(1, result.returncode)
        self.assertIn("dialectGroup 非法", result.stdout)
        self.assertIn("eligible=0", result.stdout)

    def test_mandarin_with_dialect_group_fails_evaluation(self):
        ref = jsonl(self.ref_record(speechProfileId="mandarin", dialectGroup="jilu"))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(1, result.returncode)
        self.assertIn("mandarin 的 dialectGroup 必须为 unspecified", result.stdout)
        self.assertIn("eligible=0", result.stdout)

    def test_standalone_legacy_jsonl_does_not_require_consent_flags(self):
        ref = jsonl(self.ref_record(consentFlags=[]))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("有效参考覆盖率 = 100.00% (1/1)", result.stdout)

    def test_dataset_segments_require_explicit_research_and_current_grants(self):
        cases = (
            ([], "consentFlags 必须明确包含 research_use"),
            (["train_use"], "consentFlags 必须明确包含 research_use"),
            (["research_use", "cloud_upload"], "当前未授权的用途 cloud_upload"),
        )
        for flags, expected in cases:
            with self.subTest(flags=flags):
                result = self.run_dataset_eval(self.valid_segment(consentFlags=flags))
                self.assertEqual(1, result.returncode, result.stdout)
                self.assertIn(expected, result.stdout)
                self.assertIn("eligible=0", result.stdout)
                self.assertIn("有效参考覆盖率 = 0.00% (0/0)", result.stdout)

    def test_hyp_profile_mismatch_is_rejected(self):
        hyp = jsonl({"segmentId": "s1", "text": "今天天气不错", "speechProfileId": "shandong"})
        result = self.run_eval(hyp, jsonl(self.ref_record()))
        self.assertEqual(1, result.returncode)
        self.assertIn("hyp/ref speechProfileId 不一致", result.stdout)
        self.assertIn("matched=1  missing=0  extra=0  eligible=1", result.stdout)
        self.assertIn("有效参考覆盖率 = 0.00% (0/1)", result.stdout)
        self.assertNotIn("有效参考覆盖率 = 100.00%", result.stdout)

    def test_flags_critical_error(self):
        result = self.run_eval(
            jsonl(self.hyp_record(text="我去")),
            jsonl(self.ref_record(text="我不去", category="negation")),
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("关键内容错误率 = 1.0000", result.stdout)

    def test_duplicate_hyp_id_is_rejected(self):
        hyp = jsonl(self.hyp_record()) + jsonl(self.hyp_record(text="重复"))
        result = self.run_eval(hyp, jsonl(self.ref_record()))
        self.assertEqual(2, result.returncode)
        self.assertIn("segmentId 重复", result.stdout)

    def test_duplicate_ref_id_is_rejected(self):
        ref = jsonl(self.ref_record()) + jsonl(self.ref_record(text="重复"))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(2, result.returncode)
        self.assertIn("segmentId 重复", result.stdout)

    def test_missing_hyp_fails_with_zero_coverage(self):
        result = self.run_eval("", jsonl(self.ref_record()))
        self.assertEqual(1, result.returncode)
        self.assertIn("matched=0  missing=1  extra=0", result.stdout)
        self.assertIn("有效参考覆盖率 = 0.00%", result.stdout)
        self.assertIn("缺少 hyp", result.stdout)

    def test_partial_coverage_fails_but_reports_matched_metrics(self):
        hyp = jsonl(self.hyp_record())
        ref = jsonl(self.ref_record()) + jsonl(self.ref_record(segment_id="s2"))
        result = self.run_eval(hyp, ref)
        self.assertEqual(1, result.returncode)
        self.assertIn("matched=1  missing=1  extra=0", result.stdout)
        self.assertIn("有效参考覆盖率 = 50.00%", result.stdout)
        self.assertIn("CER = 0.0000", result.stdout)

    def test_extra_hyp_fails(self):
        hyp = jsonl(self.hyp_record()) + jsonl(self.hyp_record(segment_id="extra"))
        result = self.run_eval(hyp, jsonl(self.ref_record()))
        self.assertEqual(1, result.returncode)
        self.assertIn("matched=1  missing=0  extra=1", result.stdout)
        self.assertIn("多余 segmentId", result.stdout)

    def test_unconfirmed_and_empty_capture_are_excluded(self):
        ref = "".join([
            jsonl(self.ref_record()),
            jsonl(self.ref_record(segment_id="u1", referenceSource="unconfirmed")),
            jsonl(self.ref_record(segment_id="e1", qualityFlags=["empty_capture"])),
        ])
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("matched=1  missing=0  extra=0  eligible=1", result.stdout)
        self.assertIn("unconfirmed=1", result.stdout)
        self.assertIn("empty_capture=1", result.stdout)

    def test_only_unconfirmed_is_nonzero_failure(self):
        ref = jsonl(self.ref_record(referenceSource="unconfirmed"))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(1, result.returncode)
        self.assertIn("有效参考集为空", result.stdout)

    def test_empty_normalized_ref_is_nonzero_failure(self):
        result = self.run_eval(jsonl(self.hyp_record(text="错误")), jsonl(self.ref_record(text="。")))
        self.assertEqual(1, result.returncode)
        self.assertIn("规范化后为空", result.stdout)
        self.assertNotIn("CER = 0.0000", result.stdout)

    def test_withdrawn_reference_record_is_rejected(self):
        ref = jsonl(self.ref_record(withdrawnAt="2026-11-25T10:00:00+08:00"))
        result = self.run_eval(jsonl(self.hyp_record()), ref)
        self.assertEqual(1, result.returncode)
        self.assertIn("撤回授权", result.stdout)

    def test_dataset_withdrawal_and_missing_audio_fail_real_eval_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = self.make_dataset(
                Path(tmp) / "dataset",
                self.valid_segment(),
                consent_overrides={"withdrawnAt": "2026-11-25T10:00:00+08:00"},
                missing_audio_ids={"seg_001"},
            )
            hyp = Path(tmp) / "hyp.jsonl"
            hyp.write_text(jsonl({"segmentId": "seg_001", "text": "今天天气不错"}), encoding="utf-8")
            result = run_script(
                EVALUATOR,
                "--hyp",
                str(hyp),
                "--ref",
                str(dataset / "transcripts" / "ses_001.jsonl"),
                "--manifest",
                str(dataset / "manifest.json"),
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("撤回", result.stdout)
            self.assertIn("eligible=0", result.stdout)

    def test_dataset_missing_audio_fails_real_eval_path(self):
        with tempfile.TemporaryDirectory() as tmp:
            dataset = self.make_dataset(Path(tmp) / "dataset", self.valid_segment(), missing_audio_ids={"seg_001"})
            hyp = Path(tmp) / "hyp.jsonl"
            hyp.write_text(jsonl({"segmentId": "seg_001", "text": "今天天气不错"}), encoding="utf-8")
            result = run_script(
                EVALUATOR,
                "--hyp",
                str(hyp),
                "--ref",
                str(dataset / "transcripts" / "ses_001.jsonl"),
            )
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("音频不存在", result.stdout)

    def test_dataset_metadata_enriches_layers(self):
        with tempfile.TemporaryDirectory() as tmp:
            record = self.valid_segment()
            record.pop("referenceSource")  # 旧独立 JSONL 形状仍兼容；数据集上下文提供其余元数据。
            dataset = self.make_dataset(Path(tmp) / "dataset", record)
            ref_record = dataset / "transcripts" / "ses_001.jsonl"
            payload = json.loads(ref_record.read_text(encoding="utf-8"))
            payload.pop("category", None)
            payload.pop("severityLevel", None)
            ref_record.write_text(jsonl(payload), encoding="utf-8")
            hyp = Path(tmp) / "hyp.jsonl"
            hyp.write_text(jsonl({"segmentId": "seg_001", "text": "今天天气不错"}), encoding="utf-8")
            result = run_script(EVALUATOR, "--hyp", str(hyp), "--ref", str(ref_record))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("spk_001 [L2]", result.stdout)
            self.assertIn("  daily  片段 1", result.stdout)

    def test_dataset_loads_shandong_prompt_categories(self):
        with tempfile.TemporaryDirectory() as tmp:
            record = self.valid_segment(
                promptId="SD0001",
                speechProfileId="shandong",
                dialectGroup="jiaoliao",
            )
            dataset = self.make_dataset(Path(tmp) / "dataset", record)
            ref_record = dataset / "transcripts" / "ses_001.jsonl"
            hyp = Path(tmp) / "hyp.jsonl"
            hyp.write_text(jsonl({"segmentId": "seg_001", "text": "今天天气不错"}), encoding="utf-8")
            result = run_script(EVALUATOR, "--hyp", str(hyp), "--ref", str(ref_record))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("  shandong  片段 1", result.stdout)
            self.assertIn("dialectGroup=jiaoliao", result.stdout)
            self.assertIn("  daily  片段 1", result.stdout)


class ImportSessionTest(unittest.TestCase, DatasetFactory):
    def test_import_to_new_directory_includes_prompts_and_preserves_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment())
            before = tree_bytes(source)
            dest = root / "new" / "dataset"
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertTrue((dest / "manifest.json").is_file())
            self.assertTrue((dest / "prompts" / "prompts.json").is_file())
            self.assertTrue((dest / "prompts" / "shandong.json").is_file())
            self.assertTrue((dest / "segments" / "ses_001" / "seg_001.pcm").is_file())
            self.assertEqual(before, tree_bytes(source))
            manifest = json.loads((dest / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(str(source.resolve()), manifest["provenance"]["sourcePath"])
            self.assertIn("入库完成", result.stdout)

    def test_valid_dry_run_validates_without_creating_dest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment())
            dest = root / "dest"
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest), "--dry-run")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("源目录完整性校验", result.stdout)
            self.assertIn("校验通过", result.stdout)
            self.assertFalse(dest.exists())

    def test_invalid_dry_run_fails_without_creating_dest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment(), missing_audio_ids={"seg_001"})
            dest = root / "dest"
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest), "--dry-run")
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("音频不存在", result.stdout)
            self.assertFalse(dest.exists())

    def test_same_source_and_dest_is_rejected_without_deletion(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = self.make_dataset(Path(tmp) / "source", self.valid_segment())
            before = tree_bytes(source)
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(source))
            self.assertEqual(2, result.returncode)
            self.assertIn("相同或互相包含", result.stdout)
            self.assertEqual(before, tree_bytes(source))

    def test_containment_in_either_direction_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment())
            for dest in (source / "child", root):
                with self.subTest(dest=str(dest)):
                    result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest), "--dry-run")
                    self.assertEqual(2, result.returncode)
                    self.assertTrue((source / "manifest.json").is_file())

    def test_invalid_formal_import_preserves_existing_dest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment(), missing_audio_ids={"seg_001"})
            dest = root / "dest"
            dest.mkdir()
            sentinel = dest / "keep.txt"
            sentinel.write_text("old data", encoding="utf-8")
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest))
            self.assertEqual(1, result.returncode)
            self.assertEqual("old data", sentinel.read_text(encoding="utf-8"))
            self.assertFalse(any(dest.parent.glob(".dest.staging-*")))

    def test_existing_dest_is_replaced_only_after_staging_revalidation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = self.make_dataset(root / "source", self.valid_segment())
            dest = root / "dest"
            dest.mkdir()
            (dest / "obsolete.txt").write_text("old", encoding="utf-8")
            result = run_script(IMPORTER, "--source", str(source), "--dest", str(dest))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertFalse((dest / "obsolete.txt").exists())
            self.assertTrue((dest / "prompts" / "prompts.json").is_file())
            self.assertFalse(any(dest.parent.glob(".dest.staging-*")))
            self.assertFalse(any(dest.parent.glob(".dest.backup-*")))


class ProductionPromptLibraryTest(unittest.TestCase):
    def test_shandong_assets_and_tools_are_complete_and_synchronized(self):
        root = TOOLS_DIR.parent
        asset = root / "app/src/main/assets/prompts/shandong.json"
        tools_copy = TOOLS_DIR / "prompts/shandong.json"
        self.assertTrue(asset.is_file())
        self.assertEqual(asset.read_bytes(), tools_copy.read_bytes())
        payload = json.loads(asset.read_text(encoding="utf-8"))
        self.assertEqual("v1.0", payload["version"])
        self.assertEqual("shandong", payload["speechProfileId"])
        self.assertEqual("zh-CN", payload["localeTag"])
        prompts = payload["prompts"]
        self.assertGreaterEqual(len(prompts), 30)
        self.assertEqual(len(prompts), len({item["id"] for item in prompts}))
        self.assertEqual(len(prompts), len({item["text"] for item in prompts}))
        categories = {item["category"] for item in prompts}
        self.assertEqual(
            {"daily", "negation", "number", "direction", "medical", "dialogue"},
            categories,
        )
        for item in prompts:
            self.assertTrue(item["id"].startswith("SD"))
            self.assertTrue(item["text"].strip())
            self.assertFalse(any(ch.isascii() and ch.isalpha() for ch in item["text"]), item["text"])

    def test_prompt_ids_do_not_collide_across_profiles(self):
        root = TOOLS_DIR.parent / "app/src/main/assets/prompts"
        mandarin = json.loads((root / "prompts.json").read_text(encoding="utf-8"))["prompts"]
        shandong = json.loads((root / "shandong.json").read_text(encoding="utf-8"))["prompts"]
        self.assertFalse({item["id"] for item in mandarin} & {item["id"] for item in shandong})


class PermissionCheckerTest(unittest.TestCase):
    def test_find_aapt2_uses_android_home(self):
        checker = import_tool("check_permissions")
        with tempfile.TemporaryDirectory() as tmp:
            executable = Path(tmp) / "build-tools" / "36.0.0" / "aapt2"
            executable.parent.mkdir(parents=True)
            executable.touch()
            executable.chmod(0o755)
            env = {"ANDROID_HOME": tmp, "PATH": ""}
            self.assertEqual(str(executable), checker.find_aapt2(env))

    def test_find_aapt2_uses_path(self):
        checker = import_tool("check_permissions")
        with tempfile.TemporaryDirectory() as tmp:
            executable = Path(tmp) / "aapt2"
            executable.touch()
            executable.chmod(0o755)
            env = {"ANDROID_HOME": str(Path(tmp) / "missing"), "PATH": tmp}
            self.assertEqual(str(executable), checker.find_aapt2(env))


if __name__ == "__main__":
    unittest.main(verbosity=2)
