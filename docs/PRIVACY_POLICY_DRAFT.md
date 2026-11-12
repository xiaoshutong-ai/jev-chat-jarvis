# Privacy Policy Draft — Jev Chat Assistant Fork

Last reviewed against JEV mobile source: 2026-11-11

> Working product draft. Replace publisher identity, contact email and public URL
> before release. Review against the final Play build and provider contracts.

## Overview

JEV is a mobile-first conversation assistant. For WeChat, it obtains input
from user-requested clipboard reads, optional visible notifications, or a
screenshot OCR request initiated by the user; it does not subscribe to WeChat
Accessibility events or read WeChat internal database/UI nodes. Optional
Accessibility-based reading is limited to explicitly supported QQ/X/Feishu chat
windows. Configured model services can produce three candidate replies.
Sending messages always remains a separate user action.

## Information the app accesses

Depending on the features you enable, the app may access:

- standard Android notification message previews when optional notification access is enabled;
- message text the user explicitly supplies by clipboard or manual conversation import;
- text and UI structure of the currently visible QQ/X/Feishu supported chat window when Accessibility permission is enabled;
- optional screenshots initiated by the user for local or selected remote OCR;
- relationship descriptions, notes and contacts you enter into the local
  knowledge base;
- chat history if you explicitly enable conversation history;
- API credentials and an optional up-to-500-character reply style preference you enter;
- a user-confirmed temporary 20-message context held in memory for up to 60 minutes;
- capture diagnostics such as app package name, adapter name, message count,
  capture source, timestamp and status.

The capture-diagnostics feature does not store conversation-title text, chat
message text, OCR text, API keys, prompts or model responses.

## AccessibilityService

The app uses Android AccessibilityService for supported QQ/X/Feishu chat windows
and optional accessibility screenshot functions. It does not subscribe to WeChat
Accessibility events, read WeChat node trees or automate WeChat controls.

Before you are taken to Android's Accessibility settings, the app displays a
separate disclosure explaining what is accessed, why it is needed and how data
may be transmitted.

You may refuse the request or later disable the service in Android settings.

The app does not use AccessibilityService to automatically send a message. A
candidate reply can be inserted into an input box only after your explicit
action, and you remain responsible for reviewing and sending it.

## Model providers and data sent off device

The app supports separate judgment, reply and vision routes. You select and
configure the provider.

When you request or enable a feature that needs a remote model:

- conversation text, selected stored context, and a manually imported temporary
  conversation window may be sent to the selected judgment provider;
- conversation text, relevant context, and optional user-entered style instructions
  may be sent to the selected reply provider;
- optional user-entered style instructions may also be sent to the judgment/ranking
  provider when comparing generated replies;
- if you explicitly select remote Vision OCR, a cropped image of the visible
  chat-content region may be sent to the selected vision provider;
- if you explicitly enable rolling contact summaries, stored chat history may be
  sent periodically to the selected reply provider.

The current app architecture sends those requests directly from your device to
the provider endpoint you configure. It does not proxy model traffic through a
developer-operated application server.

The app does not silently switch conversation data to another model provider
when a provider fails.

Third-party providers have their own privacy, retention and account policies.
You should review the policy and settings of the provider you choose.

## Local OCR

The default OCR engine is bundled ML Kit Chinese text recognition. In local OCR
mode, screenshot pixels and recognition output are processed on the device and
are not sent to a vision model provider by that feature. According to Google's
ML Kit terms, the SDK may separately contact Google servers for updates,
compatibility and usage/performance metrics; this is distinct from uploading
the user's input image or OCR results. See https://developers.google.com/ml-kit/terms .
Users must be informed of Google's SDK metric processing where applicable.

Remote Vision OCR is a separate, explicitly selected model-service function.

## Local history and knowledge base

Conversation history is OFF by default.

If you enable history, chat entries for contacts you have saved may be written
to the app's private local storage and used as context for later analysis.

Rolling contact summaries are also OFF by default. If enabled, the app updates a
derived summary only after enough new recorded history accumulates.

Separately, the user may temporarily import the most recent 20 messages
with explicit "me"/"other" labels. This context expires after 60 minutes in
memory. The app does not authenticate the WeChat contact identity from a
clipboard string and does not silently learn a permanent writing profile.

Knowledge-base notes and contact information that you create are stored in the
app's private local storage.

## API credentials

API credentials are stored using AES-GCM encryption backed by AndroidKeyStore.
The encryption key remains in AndroidKeyStore and encrypted values are stored in
the app's private preferences. If encryption or secure preference storage fails,
new credentials are rejected rather than persisted in plaintext. Existing legacy
plaintext credentials are migrated only after verified encrypted storage.

The app prevents implicit credential inheritance between different provider
hosts and requires HTTPS for non-loopback model endpoints.

Credentials are not intentionally written to application logs.

## Network security

Authenticated model endpoints must use HTTPS, except for localhost / loopback
development endpoints.

The app rejects model endpoint URLs containing embedded usernames/passwords,
URL fragments, unsupported schemes or cleartext non-loopback HTTP.

Authenticated requests do not automatically follow HTTP redirects.

## Logs and diagnostics

Application logs avoid chat message bodies and conversation-title text.

The optional in-app capture-diagnostics page stores only limited operational
metadata on the device. It does not store message text or OCR output.

## Data retention and deletion

You can control local data by:

- disabling conversation history;
- disabling rolling summaries;
- using local OCR instead of remote Vision OCR;
- clearing one contact's history, which also clears the summary derived from
  that history;
- clearing the knowledge base and history from settings;
- disabling AccessibilityService in Android settings;
- clearing the app's storage or uninstalling the app.

Android application backup is disabled for this app.

Data that has already been sent to a third-party model provider is governed by
that provider's retention and deletion controls.

## Data selling and advertising

The current fork does not contain an advertising SDK and does not implement a
mechanism for selling user chat data.

If advertising, analytics, crash-reporting or another data-collection SDK is
added later, this policy and the Play Data Safety declaration must be updated
before release.

## Children

This draft does not represent the app as a product specifically directed to
children. If the product is later directed to children, family/child privacy and
Google Play Families requirements must be reviewed separately before release.

## Changes

This policy must be updated when material data-access, model-routing, storage,
provider or SDK behavior changes.

## Contact

Publisher: [REPLACE BEFORE RELEASE]

Privacy contact: [REPLACE BEFORE RELEASE]

Public privacy-policy URL: [REPLACE BEFORE RELEASE]
