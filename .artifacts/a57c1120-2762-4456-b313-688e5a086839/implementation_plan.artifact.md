# Implementation Plan: Fix OAuth Hang and Support Public IP Login

This plan addresses Issue #66 where users connecting over a public IP experience a hang during the OAuth login flow (e.g., using Nous with GitHub).

## User Review Required

> [!IMPORTANT]
> This change will cause OAuth flows that were previously opened in hidden popups to now load in the **main visible WebView**. This replaces the current WebUI page during the login process. Most OIDC-compliant applications (like Hermes WebUI) handle this gracefully by rehydrating state after the callback redirect, but it is a change in behavior from "attempted hidden background login" to "visible in-app login".

## Proposed Changes

### Core Security & OAuth logic

#### [MODIFY] [OAuthPopupFlow.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/OAuthPopupFlow.kt)
- Broaden `parseAuthorizationStart` recognition:
    - Add `auth`, `login`, and `sso` as path markers.
    - Treat the simultaneous presence of `response_type=code`, `client_id`, and `redirect_uri` as a sufficient indicator of an OAuth flow even without other markers.
- Relax `redirectsToOrigin` / `matchesEndpoint`:
    - Allow `http` and `https` scheme mismatches for the origin check. Many public IP setups might have inconsistent scheme configurations between the app settings and the OAuth provider's `redirect_uri`.

#### [MODIFY] [UrlPolicy.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/core/security/UrlPolicy.kt)
- Update `UrlOrigins.hasSameOrigin` to optionally ignore the scheme if the host is a literal IP address (or just generally for OAuth callback verification).

### WebView & Activity Orchestration

#### [MODIFY] [MainActivity.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/MainActivity.kt)
- **Fix hidden popup hang**: In `handlePopupNavigation`, if a trusted OAuth flow is detected, instead of keeping it in the hidden popup, destroy the popup and load the URL in the **main WebView**.
    - This ensures the user can see and interact with the login page (e.g., entering GitHub credentials).
    - It leverages the existing native "Signing in" banner logic for the main frame.
- **Improve third-party cookie handling**: Ensure `setAcceptThirdPartyCookies` is applied consistently during the flow.
- **Mixed Content**: Evaluate changing `mixedContentMode` to `MIXED_CONTENT_COMPATIBILITY_MODE` in `HermesWebViewConfigurator.kt` to allow providers to load resources over HTTP if necessary (common in local/IP-based setups).

#### [MODIFY] [HermesWebViewConfigurator.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/webview/HermesWebViewConfigurator.kt)
- Set `mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE` for both main and popup WebViews.

## Verification Plan

### Automated Tests
- Update `OAuthPopupFlowTest.kt` to cover:
    - New path markers (`/auth`, `/login`).
    - Detection based on parameters only.
    - Scheme-insensitive origin matching.
- Run tests: `.\gradlew.bat testDebugUnitTest --tests com.hermeswebui.android.OAuthPopupFlowTest`

### Manual Verification
1. Deploy to a device.
2. Configure a server URL using an IP address.
3. Attempt a login that triggers a popup (e.g., a custom OIDC provider).
4. Verify that the login page appears in the main WebView with the "Signing in on [host]" banner.
5. Verify that finishing the login redirects back to Hermes and logs in successfully.
