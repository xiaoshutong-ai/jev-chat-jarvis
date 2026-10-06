# JEV Chat Jarvis Operations Web — Canonical Fragments

This project repository is the canonical owner of JEV Chat Jarvis-specific fragments embedded in the shared public Operations Hub shell.

Canonical runtime fragments:
- `app-runtime-specs.fragment.js`
- `app-runtime-router.fragment.js`
- `app-runtime-description.fragment.js`
- `app-runtime-group-key.fragment.js`
- `app-runtime-group-meta.fragment.js`

These runtime fragments are currently projected into `xiaoshutong-ai/mz-ph-ui/app.js`. The public shell is not the source of truth for them. Project-status copy is intentionally excluded: current status must come from project evidence/status projection rather than hard-coded shell text.

The repository remains a public MIT fork of `jev-chat/jev-chat-jarvis`; this ownership rule concerns the xiaoshutong-ai operations integration only and does not change upstream licensing or fork provenance.

Until physical module extraction is completed:
1. change JEV-specific Operations Hub behavior here first;
2. reconcile the reviewed public projection;
3. require the project Public Mirror Contract to pass;
4. require the public shell Security Gate / Pages checks to pass.

No API keys, provider credentials, private conversation data, or backend secrets belong in these public-web fragments.
