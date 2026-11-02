"""Provider request/response shapes. No network: http_post_json and boto3 are mocked."""
import base64, io, json, os, sys, types, unittest, urllib.error
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import providers
from agent import parse_action
from tests.helpers import ACTION_JSON, fake_jpeg, fake_png

IMG = fake_jpeg()
SYS, USER = "SYSTEM PROMPT", "GOAL: test"
FAKE_ENV = {
    "OPENAI_API_KEY": "test-openai", "ANTHROPIC_API_KEY": "test-anthropic", "GEMINI_API_KEY": "test-gemini",
    "XAI_API_KEY": "test-xai", "AZURE_OPENAI_ENDPOINT": "https://example-res.openai.azure.com/",
    "AZURE_OPENAI_API_KEY": "test-azure", "AZURE_OPENAI_DEPLOYMENT": "my-vision-deploy",
    "OPENROUTER_API_KEY": "test-openrouter", "MISTRAL_API_KEY": "test-mistral",
    "GOOGLE_CLOUD_PROJECT": "demo-project", "GOOGLE_CLOUD_LOCATION": "us-central1",
    "GOOGLE_VERTEX_ACCESS_TOKEN": "test-vertex-token", "AWS_REGION": "us-west-2",
}
CHAT_REPLY = {"choices": [{"message": {"role": "assistant", "content": ACTION_JSON}}]}


def clean_env(extra=None):
    env = {k: v for k, v in os.environ.items() if not any(
        k.startswith(p) for p in ("OPENAI", "ANTHROPIC", "GEMINI", "GOOGLE", "XAI", "AZURE", "OPENROUTER",
                                  "MISTRAL", "AWS", "OLLAMA"))}
    return mock.patch.dict(os.environ, {**env, **(extra or {})}, clear=True)


class Base(unittest.TestCase):
    def setUp(self):
        p = clean_env(FAKE_ENV)
        p.start()
        self.addCleanup(p.stop)

    def run_provider(self, name, reply, **kw):
        prov = providers.get_provider(name, **kw)
        url, headers, body = prov.build_request(SYS, USER, IMG)
        json.dumps(body)  # must be JSON-serialisable
        with mock.patch("providers.http_post_json", return_value=reply) as post:
            text = prov.complete(SYS, USER, IMG)
        post.assert_called_once()
        self.assertEqual(post.call_args[0][0], url)
        self.assertEqual(parse_action(text)["combo"], "win+r")
        return prov, url, headers, body


class ChatShapes(Base):
    def check_chat(self, body, model):
        if model is not None:
            self.assertEqual(body["model"], model)
        self.assertEqual(body["messages"][0], {"role": "system", "content": SYS})
        parts = body["messages"][1]["content"]
        self.assertEqual(parts[0], {"type": "text", "text": USER})
        url = parts[1]["image_url"]["url"]
        self.assertTrue(url.startswith("data:image/jpeg;base64,"))
        self.assertEqual(base64.b64decode(url.split(",", 1)[1]), IMG)

    def test_xai(self):
        _, url, h, body = self.run_provider("xai", CHAT_REPLY)
        self.assertEqual(url, "https://api.x.ai/v1/chat/completions")
        self.assertEqual(h["Authorization"], "Bearer test-xai")
        self.check_chat(body, "grok-4.7")
        self.assertEqual(body["response_format"], {"type": "json_object"})

    def test_openrouter(self):
        _, url, h, body = self.run_provider("openrouter", CHAT_REPLY, model="anthropic/claude-sonnet-5.5")
        self.assertEqual(url, "https://openrouter.ai/api/v1/chat/completions")
        self.assertEqual(h["Authorization"], "Bearer test-openrouter")
        self.assertIn("X-Title", h)
        self.check_chat(body, "anthropic/claude-sonnet-5.5")

    def test_mistral(self):
        _, url, h, body = self.run_provider("mistral", CHAT_REPLY)
        self.assertEqual(url, "https://api.mistral.ai/v1/chat/completions")
        self.assertEqual(h["Authorization"], "Bearer test-mistral")
        self.check_chat(body, "mistral-medium-latest")

    def test_azure(self):
        _, url, h, body = self.run_provider("azure-openai", CHAT_REPLY)
        self.assertEqual(url, "https://example-res.openai.azure.com/openai/deployments/my-vision-deploy"
                              "/chat/completions?api-version=2024-10-21")
        self.assertEqual(h, {"api-key": "test-azure"})
        self.assertNotIn("model", body)
        self.assertEqual(body["max_completion_tokens"], 4096)
        self.check_chat(body, None)

    def test_azure_api_version_override(self):
        with mock.patch.dict(os.environ, {"AZURE_OPENAI_API_VERSION": "2025-04-01-preview"}):
            url, _, _ = providers.get_provider("azure-openai").build_request(SYS, USER, IMG)
        self.assertTrue(url.endswith("api-version=2025-04-01-preview"))

    def test_openai_compatible_local_no_key(self):
        _, url, h, body = self.run_provider("openai-compatible", CHAT_REPLY, base_url="http://localhost:8080/v1/")
        self.assertEqual(url, "http://localhost:8080/v1/chat/completions")
        self.assertEqual(h, {})
        self.assertNotIn("response_format", body)
        self.check_chat(body, "qwen3-vl")

    def test_openai_compatible_with_key_and_env_base(self):
        with mock.patch.dict(os.environ, {"OPENAI_COMPATIBLE_BASE_URL": "http://gpu-box:8000/v1",
                                          "OPENAI_COMPATIBLE_API_KEY": "local-token"}):
            url, h, _ = providers.get_provider("openai-compatible", model="llava").build_request(SYS, USER, IMG)
        self.assertEqual(url, "http://gpu-box:8000/v1/chat/completions")
        self.assertEqual(h["Authorization"], "Bearer local-token")

    def test_openai_compatible_needs_base_url(self):
        with self.assertRaises(providers.ProviderError):
            providers.get_provider("openai-compatible").build_request(SYS, USER, IMG)

    def test_ollama_default_url(self):
        _, url, h, _ = self.run_provider("ollama", CHAT_REPLY)
        self.assertEqual(url, "http://localhost:11434/v1/chat/completions")

    def test_chat_content_parts_reply(self):
        reply = {"choices": [{"message": {"content": [{"type": "text", "text": ACTION_JSON}]}}]}
        self.run_provider("xai", reply)

    def test_chat_empty_reply_raises(self):
        prov = providers.get_provider("xai")
        with self.assertRaises(providers.ProviderError):
            prov.parse_response({"choices": [{"message": {"content": None, "refusal": "no"}}]})
        with self.assertRaises(providers.ProviderError):
            prov.parse_response({"error": "bad"})


class OpenAIShape(Base):
    def test_responses_api(self):
        reply = {"status": "completed", "output": [
            {"type": "reasoning", "summary": []},
            {"type": "message", "content": [{"type": "output_text", "text": ACTION_JSON}]}]}
        _, url, h, body = self.run_provider("openai", reply)
        self.assertEqual(url, "https://api.openai.com/v1/responses")
        self.assertEqual(h["Authorization"], "Bearer test-openai")
        self.assertEqual(body["model"], "gpt-6.1-terra")
        self.assertEqual(body["instructions"], SYS)
        c = body["input"][0]["content"]
        self.assertEqual(c[0], {"type": "input_text", "text": USER})
        self.assertEqual(c[1]["type"], "input_image")
        self.assertEqual(base64.b64decode(c[1]["image_url"].split(",", 1)[1]), IMG)
        self.assertEqual(body["text"]["format"]["type"], "json_object")

    def test_output_text_shortcut(self):
        self.run_provider("openai", {"output_text": ACTION_JSON})

    def test_model_env_override(self):
        with mock.patch.dict(os.environ, {"OPENAI_MODEL": "gpt-6-luna"}):
            self.assertEqual(providers.get_provider("openai").model, "gpt-6-luna")
        self.assertEqual(providers.get_provider("openai", model="x").model, "x")

    def test_incomplete_raises(self):
        with self.assertRaises(providers.ProviderError):
            providers.get_provider("openai").parse_response({"status": "incomplete", "output": []})


class AnthropicShape(Base):
    def test_messages_api(self):
        reply = {"content": [{"type": "thinking", "thinking": "..."}, {"type": "text", "text": ACTION_JSON}],
                 "stop_reason": "end_turn"}
        _, url, h, body = self.run_provider("anthropic", reply)
        self.assertEqual(url, "https://api.anthropic.com/v1/messages")
        self.assertEqual(h, {"x-api-key": "test-anthropic", "anthropic-version": "2023-06-01"})
        self.assertEqual(body["model"], "claude-sonnet-5-5")
        self.assertEqual(body["system"], SYS)
        img, txt = body["messages"][0]["content"]
        self.assertEqual(img["source"]["type"], "base64")
        self.assertEqual(img["source"]["media_type"], "image/jpeg")
        self.assertEqual(base64.b64decode(img["source"]["data"]), IMG)
        self.assertEqual(txt, {"type": "text", "text": USER})

    def test_png_media_type(self):
        _, _, body = providers.get_provider("anthropic").build_request(SYS, USER, fake_png())
        self.assertEqual(body["messages"][0]["content"][0]["source"]["media_type"], "image/png")


GEMINI_REPLY = {"candidates": [{"content": {"parts": [{"text": "thinking...", "thought": True},
                                                       {"text": ACTION_JSON}]}, "finishReason": "STOP"}]}


class GoogleShapes(Base):
    def check_body(self, body):
        self.assertEqual(body["systemInstruction"]["parts"], [{"text": SYS}])
        img, txt = body["contents"][0]["parts"]
        self.assertEqual(img["inline_data"]["mime_type"], "image/jpeg")
        self.assertEqual(base64.b64decode(img["inline_data"]["data"]), IMG)
        self.assertEqual(txt, {"text": USER})
        self.assertEqual(body["generationConfig"]["responseMimeType"], "application/json")

    def test_gemini(self):
        _, url, h, body = self.run_provider("gemini", GEMINI_REPLY)
        self.assertEqual(url, "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
        self.assertEqual(h, {"x-goog-api-key": "test-gemini"})
        self.assertNotIn("key=", url)  # key goes in a header, not the URL
        self.check_body(body)

    def test_vertex_regional(self):
        _, url, h, body = self.run_provider("google-vertex", GEMINI_REPLY)
        self.assertEqual(url, "https://us-central1-aiplatform.googleapis.com/v1/projects/demo-project/locations/"
                              "us-central1/publishers/google/models/gemini-3.8-flash:generateContent")
        self.assertEqual(h, {"Authorization": "Bearer test-vertex-token"})
        self.check_body(body)

    def test_vertex_global(self):
        with mock.patch.dict(os.environ, {"GOOGLE_CLOUD_LOCATION": "global"}):
            url, _, _ = providers.get_provider("google-vertex").build_request(SYS, USER, IMG)
        self.assertTrue(url.startswith("https://aiplatform.googleapis.com/v1/projects/demo-project/locations/global/"))

    def test_gemini_blocked(self):
        with self.assertRaises(providers.ProviderError):
            providers.get_provider("gemini").parse_response({"promptFeedback": {"blockReason": "SAFETY"}})


class BedrockShape(Base):
    def test_converse(self):
        calls = {}

        class FakeClient:
            def converse(self, **kw):
                calls.update(kw)
                return {"output": {"message": {"role": "assistant", "content": [{"text": ACTION_JSON}]}},
                        "stopReason": "end_turn"}

        fake = types.ModuleType("boto3")
        fake.client = lambda svc, region_name=None: (calls.update(svc=svc, region=region_name), FakeClient())[1]
        with mock.patch.dict(sys.modules, {"boto3": fake}):
            prov = providers.get_provider("aws-bedrock")
            text = prov.complete(SYS, USER, IMG)
        self.assertEqual(parse_action(text)["type"], "key")
        self.assertEqual(calls["svc"], "bedrock-runtime")
        self.assertEqual(calls["region"], "us-west-2")
        self.assertEqual(calls["modelId"], "anthropic.claude-sonnet-5-5")
        self.assertEqual(calls["system"], [{"text": SYS}])
        img, txt = calls["messages"][0]["content"]
        self.assertEqual(img["image"]["format"], "jpeg")
        self.assertEqual(img["image"]["source"]["bytes"], IMG)  # raw bytes; boto3 encodes them
        self.assertEqual(txt, {"text": USER})
        self.assertEqual(calls["inferenceConfig"], {"maxTokens": 4096})

    def test_missing_boto3(self):
        with mock.patch.dict(sys.modules, {"boto3": None}):
            with self.assertRaises(providers.ProviderError) as cm:
                providers.get_provider("aws-bedrock").complete(SYS, USER, IMG)
        self.assertIn("pip install boto3", str(cm.exception))


class Config(unittest.TestCase):
    def test_missing_keys(self):
        with clean_env():
            for name in ("openai", "anthropic", "gemini", "xai", "azure-openai", "openrouter", "mistral"):
                with self.subTest(name), self.assertRaises(providers.ProviderError):
                    providers.get_provider(name).build_request(SYS, USER, IMG)

    def test_unknown_provider(self):
        with self.assertRaises(providers.ProviderError):
            providers.get_provider("nope")

    def test_all_have_defaults(self):
        for name, cls in providers.PROVIDERS.items():
            if name != "azure-openai":
                self.assertTrue(cls.default_model, name)


class Http(unittest.TestCase):
    def http_error(self, code, body=b'{"error":"x"}'):
        return urllib.error.HTTPError("https://api.example/v1/x", code, "err", {}, io.BytesIO(body))

    def test_retry_then_success(self):
        ok = mock.MagicMock()
        ok.__enter__.return_value.read.return_value = b'{"ok": true}'
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(429), ok]) as u, \
                mock.patch("time.sleep"):
            self.assertEqual(providers.http_post_json("https://api.example/v1/x", {"A": "b"}, {"q": 1}), {"ok": True})
        self.assertEqual(u.call_count, 2)
        req = u.call_args[0][0]
        self.assertEqual(json.loads(req.data), {"q": 1})
        self.assertEqual(req.get_header("Content-type"), "application/json")

    def test_client_error_no_retry(self):
        with mock.patch("urllib.request.urlopen", side_effect=self.http_error(401)) as u:
            with self.assertRaises(providers.ProviderError) as cm:
                providers.http_post_json("https://api.example/v1/x?key=SECRET", {}, {})
        self.assertEqual(u.call_count, 1)
        self.assertIn("HTTP 401", str(cm.exception))
        self.assertNotIn("SECRET", str(cm.exception))


if __name__ == "__main__":
    unittest.main()
