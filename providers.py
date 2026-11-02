"""Vision-LLM "brains" for agent.py: one small adapter per cloud / local provider.

Every provider turns (system prompt, user text, image bytes) into one HTTP request and
returns the model's text reply. Plain urllib only; heavy SDKs (boto3 for AWS Bedrock,
google-auth for Vertex AI) are optional imports used only by the provider that needs them.

API keys come from environment variables and are never logged or hardcoded.

  from providers import get_provider
  brain = get_provider("anthropic")            # reads ANTHROPIC_API_KEY
  text = brain.complete(system, user_text, jpeg_bytes)
"""
import base64, json, os, shutil, subprocess, time, urllib.error, urllib.request


class ProviderError(RuntimeError):
    pass


def image_mime(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def b64(data):
    return base64.b64encode(data).decode("ascii")


def data_url(data):
    return f"data:{image_mime(data)};base64,{b64(data)}"


def http_post_json(url, headers, body, timeout=120, retries=2):
    """POST JSON, return parsed JSON. Retries on 429/5xx with backoff. Tests patch this."""
    data = json.dumps(body).encode()
    last = None
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, data=data, method="POST",
                                     headers={"Content-Type": "application/json", **headers})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")[:800]
            last = ProviderError(f"HTTP {e.code} from {url.split('?')[0]}: {text}")
            if e.code not in (429, 500, 502, 503, 504, 529):
                raise last
        except (urllib.error.URLError, OSError) as e:
            last = ProviderError(f"cannot reach {url.split('?')[0]}: {e}")
        if attempt < retries:
            time.sleep(2 * (attempt + 1))
    raise last


def need_env(*names):
    for n in names:
        v = os.environ.get(n)
        if v:
            return v
    raise ProviderError(f"set {' or '.join(names)} in the environment")


class Provider:
    name = "?"
    default_model = None
    json_mode = False

    def __init__(self, model=None, base_url=None, max_tokens=4096, timeout=120):
        self.model = model or os.environ.get(f"{self.name.upper().replace('-', '_')}_MODEL") or self.default_model
        self.base_url = base_url
        self.max_tokens = max_tokens
        self.timeout = timeout

    def build_request(self, system, user_text, image):
        """-> (url, headers, json_body)"""
        raise NotImplementedError

    def parse_response(self, resp):
        """provider JSON -> reply text"""
        raise NotImplementedError

    def complete(self, system, user_text, image):
        url, headers, body = self.build_request(system, user_text, image)
        return self.parse_response(http_post_json(url, headers, body, timeout=self.timeout))

    def describe(self):
        return f"{self.name}:{self.model}"


# ---- OpenAI Chat Completions shape (xAI, Azure, OpenRouter, Mistral, local servers) ----

class ChatCompletions(Provider):
    base = None
    key_env = ()
    key_optional = False
    max_tokens_field = "max_tokens"

    def endpoint(self):
        return (self.base_url or self.base).rstrip("/") + "/chat/completions"

    def auth_headers(self):
        if self.key_optional:
            key = next((os.environ[n] for n in self.key_env if os.environ.get(n)), None)
        else:
            key = need_env(*self.key_env)
        return {"Authorization": f"Bearer {key}"} if key else {}

    def build_request(self, system, user_text, image):
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": [
                    {"type": "text", "text": user_text},
                    {"type": "image_url", "image_url": {"url": data_url(image)}},
                ]},
            ],
            self.max_tokens_field: self.max_tokens,
        }
        if self.json_mode:
            body["response_format"] = {"type": "json_object"}
        return self.endpoint(), self.auth_headers(), body

    def parse_response(self, resp):
        try:
            msg = resp["choices"][0]["message"]
        except (KeyError, IndexError, TypeError):
            raise ProviderError(f"unexpected response: {json.dumps(resp)[:500]}")
        c = msg.get("content")
        if isinstance(c, list):  # some servers return content parts
            c = "".join(p.get("text", "") for p in c if isinstance(p, dict))
        if not c:
            raise ProviderError(f"empty reply (refusal={msg.get('refusal')!r})")
        return c


class XAI(ChatCompletions):
    name, default_model, base, key_env, json_mode = "xai", "grok-4.7", "https://api.x.ai/v1", ("XAI_API_KEY",), True


class OpenRouter(ChatCompletions):
    name, default_model, base, key_env = "openrouter", "google/gemini-3.8-flash", "https://openrouter.ai/api/v1", ("OPENROUTER_API_KEY",)

    def auth_headers(self):
        return super().auth_headers() | {"HTTP-Referer": "https://github.com/TravelerHacker/helpful-potato",
                                         "X-Title": "Helpful Potato"}


class Mistral(ChatCompletions):
    name, default_model, base, key_env, json_mode = "mistral", "mistral-medium-latest", "https://api.mistral.ai/v1", ("MISTRAL_API_KEY",), True


class OpenAICompatible(ChatCompletions):
    """Any server speaking /v1/chat/completions: Ollama, llama.cpp server, vLLM, LM Studio, LocalAI..."""
    name, default_model, key_env, key_optional = "openai-compatible", "qwen3-vl", ("OPENAI_COMPATIBLE_API_KEY",), True

    def endpoint(self):
        base = self.base_url or os.environ.get("OPENAI_COMPATIBLE_BASE_URL")
        if not base:
            raise ProviderError("openai-compatible needs --base-url (or OPENAI_COMPATIBLE_BASE_URL), "
                                "e.g. http://localhost:11434/v1 for Ollama")
        return base.rstrip("/") + "/chat/completions"


class Ollama(OpenAICompatible):
    name = "ollama"

    def endpoint(self):
        base = self.base_url or os.environ.get("OLLAMA_BASE_URL") or "http://localhost:11434/v1"
        return base.rstrip("/") + "/chat/completions"


class AzureOpenAI(ChatCompletions):
    name, json_mode, max_tokens_field = "azure-openai", True, "max_completion_tokens"
    default_api_version = "2024-10-21"

    def __init__(self, model=None, **kw):
        super().__init__(model=model, **kw)
        self.model = model or os.environ.get("AZURE_OPENAI_DEPLOYMENT")

    def endpoint(self):
        ep = (self.base_url or need_env("AZURE_OPENAI_ENDPOINT")).rstrip("/")
        if not self.model:
            raise ProviderError("set AZURE_OPENAI_DEPLOYMENT (or --model) to your deployment name")
        ver = os.environ.get("AZURE_OPENAI_API_VERSION", self.default_api_version)
        return f"{ep}/openai/deployments/{self.model}/chat/completions?api-version={ver}"

    def auth_headers(self):
        return {"api-key": need_env("AZURE_OPENAI_API_KEY", "AZURE_OPENAI_KEY")}

    def build_request(self, system, user_text, image):
        url, headers, body = super().build_request(system, user_text, image)
        body.pop("model")  # the deployment in the URL selects the model
        return url, headers, body


# ---- OpenAI Responses API ----

class OpenAI(Provider):
    name, default_model, json_mode = "openai", "gpt-6.1-terra", True

    def build_request(self, system, user_text, image):
        base = (self.base_url or os.environ.get("OPENAI_BASE_URL") or "https://api.openai.com/v1").rstrip("/")
        body = {
            "model": self.model,
            "instructions": system,
            "input": [{"role": "user", "content": [
                {"type": "input_text", "text": user_text},
                {"type": "input_image", "image_url": data_url(image), "detail": "high"},
            ]}],
            "max_output_tokens": self.max_tokens,
            "text": {"format": {"type": "json_object"}},
            "store": False,
        }
        return base + "/responses", {"Authorization": f"Bearer {need_env('OPENAI_API_KEY')}"}, body

    def parse_response(self, resp):
        if resp.get("output_text"):
            return resp["output_text"]
        texts = [c.get("text", "") for item in resp.get("output", []) if item.get("type") == "message"
                 for c in item.get("content", []) if c.get("type") == "output_text"]
        if not any(texts):
            raise ProviderError(f"no text in response (status={resp.get('status')}, "
                                f"incomplete={resp.get('incomplete_details')})")
        return "".join(texts)


# ---- Anthropic Messages API ----

class Anthropic(Provider):
    name, default_model = "anthropic", "claude-sonnet-5-5"

    def build_request(self, system, user_text, image):
        base = (self.base_url or "https://api.anthropic.com").rstrip("/")
        body = {
            "model": self.model,
            "max_tokens": self.max_tokens,
            "system": system,
            "messages": [{"role": "user", "content": [
                {"type": "image", "source": {"type": "base64", "media_type": image_mime(image), "data": b64(image)}},
                {"type": "text", "text": user_text},
            ]}],
        }
        headers = {"x-api-key": need_env("ANTHROPIC_API_KEY"), "anthropic-version": "2023-06-01"}
        return base + "/v1/messages", headers, body

    def parse_response(self, resp):
        texts = [b.get("text", "") for b in resp.get("content", []) if b.get("type") == "text"]
        if not any(texts):
            raise ProviderError(f"no text block in response (stop_reason={resp.get('stop_reason')})")
        return "".join(texts)


# ---- Google Gemini (AI Studio) and Vertex AI ----

class Gemini(Provider):
    name, default_model, json_mode = "gemini", "gemini-3.8-flash", True

    def body(self, system, user_text, image):
        return {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [
                {"inline_data": {"mime_type": image_mime(image), "data": b64(image)}},
                {"text": user_text},
            ]}],
            "generationConfig": {"maxOutputTokens": self.max_tokens, "responseMimeType": "application/json"},
        }

    def build_request(self, system, user_text, image):
        base = (self.base_url or "https://generativelanguage.googleapis.com/v1beta").rstrip("/")
        key = need_env("GEMINI_API_KEY", "GOOGLE_API_KEY")
        return (f"{base}/models/{self.model}:generateContent", {"x-goog-api-key": key},
                self.body(system, user_text, image))

    def parse_response(self, resp):
        try:
            cand = resp["candidates"][0]
        except (KeyError, IndexError, TypeError):
            raise ProviderError(f"no candidates (promptFeedback={resp.get('promptFeedback')})")
        texts = [p.get("text", "") for p in cand.get("content", {}).get("parts", []) if not p.get("thought")]
        if not any(texts):
            raise ProviderError(f"empty candidate (finishReason={cand.get('finishReason')})")
        return "".join(texts)


class GoogleVertex(Gemini):
    """Gemini on Vertex AI. Auth: GOOGLE_VERTEX_ACCESS_TOKEN, else google-auth (optional), else gcloud."""
    name = "google-vertex"

    def token(self):
        tok = os.environ.get("GOOGLE_VERTEX_ACCESS_TOKEN")
        if tok:
            return tok
        try:
            import google.auth, google.auth.transport.requests  # optional dependency
            creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
            creds.refresh(google.auth.transport.requests.Request())
            return creds.token
        except ImportError:
            pass
        if shutil.which("gcloud"):
            r = subprocess.run(["gcloud", "auth", "print-access-token"], capture_output=True, text=True)
            if r.returncode == 0 and r.stdout.strip():
                return r.stdout.strip()
        raise ProviderError("google-vertex: set GOOGLE_VERTEX_ACCESS_TOKEN, `pip install google-auth`, "
                            "or log in with `gcloud auth application-default login`")

    def build_request(self, system, user_text, image):
        project = need_env("GOOGLE_CLOUD_PROJECT")
        loc = os.environ.get("GOOGLE_CLOUD_LOCATION", "global")
        host = "aiplatform.googleapis.com" if loc == "global" else f"{loc}-aiplatform.googleapis.com"
        base = (self.base_url or f"https://{host}/v1").rstrip("/")
        url = f"{base}/projects/{project}/locations/{loc}/publishers/google/models/{self.model}:generateContent"
        body = self.body(system, user_text, image)
        body["systemInstruction"]["role"] = "system"
        return url, {"Authorization": f"Bearer {self.token()}"}, body


# ---- AWS Bedrock (Converse API via boto3, optional) ----

class AWSBedrock(Provider):
    name, default_model = "aws-bedrock", "anthropic.claude-sonnet-5-5"

    def build_request(self, system, user_text, image):
        fmt = image_mime(image).split("/")[1]
        kwargs = {
            "modelId": self.model,
            "system": [{"text": system}],
            "messages": [{"role": "user", "content": [
                {"image": {"format": fmt, "source": {"bytes": image}}},
                {"text": user_text},
            ]}],
            "inferenceConfig": {"maxTokens": self.max_tokens},
        }
        return "bedrock-runtime:converse", {}, kwargs

    def client(self):
        try:
            import boto3  # optional dependency
        except ImportError:
            raise ProviderError("aws-bedrock needs boto3: pip install boto3 (uses standard AWS credentials)")
        region = os.environ.get("AWS_REGION") or os.environ.get("AWS_DEFAULT_REGION") or "us-east-1"
        return boto3.client("bedrock-runtime", region_name=region)

    def complete(self, system, user_text, image):
        _, _, kwargs = self.build_request(system, user_text, image)
        try:
            resp = self.client().converse(**kwargs)
        except ProviderError:
            raise
        except Exception as e:  # botocore ClientError etc.
            raise ProviderError(f"bedrock: {e}")
        return self.parse_response(resp)

    def parse_response(self, resp):
        try:
            blocks = resp["output"]["message"]["content"]
        except (KeyError, TypeError):
            raise ProviderError(f"unexpected bedrock response (stopReason={resp.get('stopReason')})")
        texts = [b["text"] for b in blocks if "text" in b]
        if not any(texts):
            raise ProviderError(f"no text in bedrock response (stopReason={resp.get('stopReason')})")
        return "".join(texts)


PROVIDERS = {p.name: p for p in (OpenAI, Anthropic, Gemini, XAI, AzureOpenAI, AWSBedrock, GoogleVertex,
                                 OpenRouter, Mistral, OpenAICompatible, Ollama)}

# env var(s) holding each provider's secret; agent.py refuses to type these values
SECRET_ENVS = ("OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "XAI_API_KEY",
               "AZURE_OPENAI_API_KEY", "AZURE_OPENAI_KEY", "OPENROUTER_API_KEY", "MISTRAL_API_KEY",
               "OPENAI_COMPATIBLE_API_KEY", "GOOGLE_VERTEX_ACCESS_TOKEN", "AWS_SECRET_ACCESS_KEY",
               "AWS_SESSION_TOKEN", "AWS_ACCESS_KEY_ID")


def get_provider(name, **kw):
    try:
        cls = PROVIDERS[name]
    except KeyError:
        raise ProviderError(f"unknown provider {name!r}; choose from: {', '.join(PROVIDERS)}")
    return cls(**kw)
