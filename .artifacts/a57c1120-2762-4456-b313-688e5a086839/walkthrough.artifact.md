# Walkthrough - Fixing OAuth Hang and Public IP Login (Issue #66)

I have implemented a set of changes to resolve the issue where users connecting via a public IP experience a hang during OAuth logins (like Nous/GitHub).

## Changes Made

### 1. Fixed Hidden Popup Hang
OAuth providers often open in a popup. The app previously kept these popups hidden in the background. If a login required user input (like typing a password on GitHub), the user could never see the form, causing a hang.
- **Solution**: Popup OAuth flows are now intercepted and forced into the **main visible WebView**. This ensures the user can see and interact with the login page.
- [MainActivity.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/MainActivity.kt)

### 2. Broadened OAuth Recognition
Some OIDC providers use non-standard paths (e.g., `/auth`, `/login`, `/sso`) or omit optional markers like `state`.
- **Solution**: Updated `OAuthPopupFlow` to recognize more path markers and added a fallback detection that identifies an OAuth flow if the core required parameters (`response_type=code`, `client_id`, `redirect_uri`) are all present.
- [OAuthPopupFlow.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/OAuthPopupFlow.kt)

### 3. Scheme-Insensitive Origin Matching
Public IP and local network setups often have scheme mismatches (e.g., the app is configured for `http://1.2.3.4` but the OAuth callback uses `https://1.2.3.4`).
- **Solution**: Relaxed the origin check to allow `http` and `https` mismatches as long as the host and port are identical.
- [UrlPolicy.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/core/security/UrlPolicy.kt)

### 4. Enabled Mixed Content Compatibility
Logins on local networks may transition between secure and insecure contexts.
- **Solution**: Changed the WebView mixed content mode from `NEVER_ALLOW` to `COMPATIBILITY_MODE`.
- [HermesWebViewConfigurator.kt](file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/webview/HermesWebViewConfigurator.kt)

### 5. API Compatibility Cleanup
Fixed a regression where `URLDecoder.decode` was using a Charset overload introduced in API 33, which would crash on older devices. Reverted to the String-based charset overload compatible with API 26+.

## Verification Results

### Automated Tests
- Updated `OAuthPopupFlowTest.kt` with new test cases for path markers, parameter-based detection, and scheme leniency.
- Verified the logic handles both IP-based and domain-based OAuth flows correctly.

> [!NOTE]
> Regarding your concern about WebUI reloading: Most OIDC-compliant applications (including Hermes WebUI) are designed to handle this. The redirect back to the `redirect_uri` serves as a signal to re-initialize and consume the authorization code. This is much more reliable than attempting to manage hidden background state for interactive logins.

render_diffs(file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/OAuthPopupFlow.kt)
render_diffs(file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/MainActivity.kt)
render_diffs(file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/core/security/UrlPolicy.kt)
render_diffs(file:///E:/GitHub/Personal/hermes-android/app/src/main/java/com/hermeswebui/android/webview/HermesWebViewConfigurator.kt)
